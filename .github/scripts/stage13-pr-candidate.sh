#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

tools="$ANDROID_HOME/build-tools/35.0.0"
input="$RUNNER_TEMP/stage13-input/app-release-unsigned.apk"
output="$RUNNER_TEMP/stage13-signed"
expected="$(python3 qa/production_policy.py certificateSha256)"

check_apk() {
  local apk="$1"
  local badging
  badging="$("$tools/aapt2" dump badging "$apk")"
  local package
  package="$(awk '/^package:/{print; exit}' <<< "$badging")"
  [[ "$package" == *"name='com.walkmanstudio.nativeplayer'"* ]]
  [[ "$package" == *"versionCode='110'"* && "$package" == *"versionName='1.5.0'"* ]]
  grep -Fx "minSdkVersion:'26'" <<< "$badging"
  grep -Fx "targetSdkVersion:'35'" <<< "$badging"
  if grep -q '^application-debuggable' <<< "$badging"; then
    echo 'Debuggable APK is ineligible for production signing' >&2
    return 1
  fi
  "$tools/zipalign" -c -P 16 4 "$apk"

  python3 - "$apk" "$expected" <<'PY'
import re
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as apk:
    names = apk.namelist()
    assert len(names) == len(set(names)), "Duplicate ZIP member"
    abis = {name.split("/")[1] for name in names if name.startswith("lib/") and name.endswith(".so")}
    assert abis == {"arm64-v8a", "x86_64"}, abis
    assert all(f"lib/{abi}/libwalkman_dsd.so" in names for abi in abis)
    pin = sys.argv[2].encode("ascii") + b"\0"
    assert any(pin in apk.read(name) for name in names if re.fullmatch(r"classes\d*\.dex", name))
PY
}

case "${1:-}" in
  verify)
    [[ -f "$input" && ! -L "$input" ]]
    [[ "$(find "$RUNNER_TEMP/stage13-input" -mindepth 1 -maxdepth 1 | wc -l)" -eq 1 ]]
    check_apk "$input"
    if "$tools/apksigner" verify "$input" >/dev/null 2>&1; then
      echo 'Expected unsigned build input' >&2
      exit 1
    fi
    ;;
  sign)
    [[ "$SOURCE_SHA" =~ ^[a-f0-9]{40}$ ]]
    [[ "$BACKED_UP_CERTIFICATE" == "$expected" ]]
    for name in WALKMAN_SIGNING_KEYSTORE_B64 WALKMAN_SIGNING_STORE_PASSWORD WALKMAN_SIGNING_KEY_ALIAS WALKMAN_SIGNING_KEY_PASSWORD; do
      if [[ -z "${!name}" ]]; then
        printf 'Required signing secret missing: %s\n' "$name" >&2
        exit 1
      fi
    done
    umask 077
    keystore="$(mktemp "$RUNNER_TEMP/walkman-keystore.XXXXXXXX")"
    trap 'rm -f -- "$keystore"' EXIT
    printf '%s' "$WALKMAN_SIGNING_KEYSTORE_B64" | base64 --decode > "$keystore"
    unset WALKMAN_SIGNING_KEYSTORE_B64
    certificate="$(keytool -exportcert -storetype PKCS12 -keystore "$keystore" \
      -alias "$WALKMAN_SIGNING_KEY_ALIAS" -storepass:env WALKMAN_SIGNING_STORE_PASSWORD | sha256sum)"
    [[ "${certificate%% *}" == "$expected" ]]
    mkdir "$output"
    signed="$output/mio-player-1.5.0-pr4.apk"
    "$tools/apksigner" sign --ks "$keystore" --ks-type PKCS12 \
      --ks-key-alias "$WALKMAN_SIGNING_KEY_ALIAS" \
      --ks-pass env:WALKMAN_SIGNING_STORE_PASSWORD \
      --key-pass env:WALKMAN_SIGNING_KEY_PASSWORD --out "$signed" "$input"
    rm -f -- "$keystore"
    unset WALKMAN_SIGNING_STORE_PASSWORD WALKMAN_SIGNING_KEY_PASSWORD WALKMAN_SIGNING_KEY_ALIAS
    check_apk "$signed"
    "$tools/apksigner" verify --verbose --print-certs-pem "$signed" > "$output/SIGNATURE.txt"
    PYTHONPATH=qa python3 - "$output/SIGNATURE.txt" <<'PY'
from pathlib import Path
import sys

from production_policy import read_policy, verify_production_signature

verify_production_signature(Path(sys.argv[1]).read_text(), read_policy())
PY
    printf 'PR #4 source: %s\nTrusted workflow revision: %s\nRun: %s/actions/runs/%s\n' \
      "$SOURCE_SHA" "$GITHUB_SHA" "$GITHUB_SERVER_URL/$GITHUB_REPOSITORY" "$GITHUB_RUN_ID" > "$output/PROVENANCE.txt"
    (cd "$output"; sha256sum mio-player-1.5.0-pr4.apk SIGNATURE.txt PROVENANCE.txt > SHA256SUMS)
    ;;
  *)
    echo 'Expected verify or sign' >&2
    exit 2
    ;;
esac
