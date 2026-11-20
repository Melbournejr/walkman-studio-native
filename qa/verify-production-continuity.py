#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Validate the production candidate against the actual latest public APK."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

from production_policy import LEGACY_CERTIFICATE, read_policy

if not __debug__:
    sys.exit("Continuity verification requires Python assertions")

tools, release_json, candidate = map(Path, sys.argv[1:])
policy = read_policy()
release = json.loads(release_json.read_text())
assert release["draft"] is False and release["prerelease"] is False
tag = release["tag_name"]
assert re.fullmatch(r"stage[0-9]+", tag) and tag != policy["tag"]
assets = [asset for asset in release["assets"] if asset["name"] == f"walkman-native-{tag}.apk"]
assert len(assets) == 1
asset = assets[0]
url = f"https://github.com/Melbournejr/walkman-studio-native/releases/download/{tag}/{asset['name']}"
assert asset["browser_download_url"] == url


def identity(apk):
    output = subprocess.check_output(
        [tools / "apksigner", "verify", "--verbose", "--print-certs", apk], text=True,
    ).splitlines()
    assert "Number of signers: 1" in output
    assert "Verified using v2 scheme (APK Signature Scheme v2): true" in output
    prefix = "Signer #1 certificate SHA-256 digest: "
    cert = next(line.removeprefix(prefix) for line in output if line.startswith(prefix))
    badging = subprocess.check_output([tools / "aapt2", "dump", "badging", apk], text=True)
    assert "package: name='com.walkmanstudio.nativeplayer'" in badging
    return int(re.search(r"versionCode='([0-9]+)'", badging)[1]), cert


with tempfile.TemporaryDirectory() as temp:
    previous = Path(temp) / "previous.apk"
    subprocess.run(
        ["curl", "-fsSL", "--proto", "=https", "--proto-redir", "=https", "--max-filesize",
         str(128 * 1024 * 1024), "-o", previous, url],
        check=True,
    )
    actual = hashlib.sha256(previous.read_bytes()).hexdigest()
    assert previous.stat().st_size == asset["size"]
    if tag == "stage11":
        assert actual == "0771ab03c70cc6b63544008933fda3079cfc04514769192bc10140b1e97423da"
    else:
        assert asset["digest"] == f"sha256:{actual}"
    previous_code, previous_signer = identity(previous)

code, signer = identity(candidate)
assert code == int(policy["versionCode"]) and code > previous_code
assert signer == policy["certificateSha256"]
if tag == "stage11":
    assert policy["tag"] == "stage12" and code == 108
    assert previous_code == 107 and previous_signer == LEGACY_CERTIFICATE
else:
    assert int(tag[5:]) >= 12 and int(policy["tag"][5:]) > int(tag[5:])
    assert previous_signer == signer, "Production OTA signer changed"
print(f"Verified release progression from version {previous_code} to {code}")
