#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

packages=('build-tools;35.0.0')
required_executables=(build-tools/35.0.0/{aapt2,apksigner,zipalign})
case "${1:-}" in
  build)
    packages+=('platforms;android-36' 'cmake;3.22.1' 'ndk;30.0.16248370')
    required_executables+=(cmake/3.22.1/bin/cmake ndk/30.0.16248370/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf)
    ;;
  sign)
    packages+=('ndk;30.0.16248370')
    required_executables+=(ndk/30.0.16248370/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf)
    ;;
  publish) ;;
  *) echo 'Usage: setup-android-sdk.sh build|sign|publish' >&2; exit 2 ;;
esac

: "${RUNNER_TEMP:?Missing runner temporary directory}" "${GITHUB_ENV:?Missing GitHub environment file}" "${GITHUB_PATH:?Missing GitHub path file}"
export JAVA_HOME="${JAVA_HOME_21_X64:?JDK 21 is required}"
export PATH="$JAVA_HOME/bin:$PATH"
java_version="$("$JAVA_HOME/bin/java" -version 2>&1)"
printf '%s\n' "$java_version"
[[ "$java_version" =~ version\ \"21([.\"]|$) ]] || { echo 'Expected JDK 21' >&2; exit 1; }

export ANDROID_HOME="$RUNNER_TEMP/walkman-android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$RUNNER_TEMP/walkman-android-user"
scratch="$(mktemp -d "$RUNNER_TEMP/walkman-sdk-download.XXXXXXXX")"
trap 'rm -rf -- "$scratch"' EXIT

# Google Command-line Tools 19.0, Linux archive build 13114758.
archive="$scratch/command-line-tools.zip"
curl --fail --silent --show-error --location --retry 3 --proto '=https' --proto-redir '=https' \
  https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip --output "$archive"
printf '%s  %s\n' 7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee "$archive" | sha256sum --check
unzip -q "$archive" -d "$scratch"
mkdir -p "$ANDROID_HOME/cmdline-tools" "$ANDROID_USER_HOME"
mv "$scratch/cmdline-tools" "$ANDROID_HOME/cmdline-tools/19.0"

sdkmanager="$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager"
test -x "$sdkmanager"
printf 'sdkmanager executable: %s\n' "$sdkmanager"
"$sdkmanager" --sdk_root="$ANDROID_HOME" --version

# Preserve sdkmanager's status when yes receives SIGPIPE after the last prompt.
if ! (set +o pipefail; yes | "$sdkmanager" --sdk_root="$ANDROID_HOME" --licenses) > "$scratch/licenses.log" 2>&1; then
  cat "$scratch/licenses.log" >&2
  exit 1
fi
printf 'Android SDK licenses accepted.\n'

# Resolve the explicit package IDs across SDK channels.
"$sdkmanager" --sdk_root="$ANDROID_HOME" --channel=3 --install "${packages[@]}"
installed="$("$sdkmanager" --sdk_root="$ANDROID_HOME" --list_installed)"
for package in "${packages[@]}"; do
  awk -F '|' -v package="$package" '{gsub(/^[ \t]+|[ \t]+$/, "", $1)} $1 == package {print; found=1} END {exit !found}' <<< "$installed" ||
    { echo "Android SDK package missing: $package" >&2; exit 1; }
done
for executable in "${required_executables[@]}"; do
  test -x "$ANDROID_HOME/$executable" || { echo "Android SDK executable missing: $executable" >&2; exit 1; }
done
if [[ "$1" == build ]]; then
  test -r "$ANDROID_HOME/platforms/android-36/android.jar"
fi

printf 'JAVA_HOME=%s\nANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\nANDROID_USER_HOME=%s\n' \
  "$JAVA_HOME" "$ANDROID_HOME" "$ANDROID_SDK_ROOT" "$ANDROID_USER_HOME" >> "$GITHUB_ENV"
printf '%s\n' "$JAVA_HOME/bin" "$(dirname "$sdkmanager")" >> "$GITHUB_PATH"
