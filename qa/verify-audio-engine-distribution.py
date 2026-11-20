#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Verify the reviewed Stage 12 distribution independently of the signing host."""
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

if not __debug__:
    sys.exit("Distribution validation requires Python assertions to be enabled")

tools, output = map(Path, sys.argv[1:])
stage = Path(__file__).resolve().parents[1] / "distribution/stage12"
apk_name = "walkman-native-stage12.apk"
source_name = "walkman-native-stage12-code.zip"
pin = "2354b7de5b85a17540a1b7e43b0c66e49295ea353c9f17c33d54e9f42338dff2"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


manifest = json.loads((stage / "READY.json").read_text())
assert manifest["versionCode"] == 108 and manifest["versionName"] == "1.4.0-audio-engine"
assert manifest["certificateSha256"] == pin
records = manifest["files"]
assert len(records) == 2 and {item["name"] for item in records} == {apk_name, source_name}
output.mkdir()
for record in records:
    prefix = "apk" if record["name"] == apk_name else "source"
    chunks = []
    for index, part in enumerate(record["parts"]):
        name = f"{prefix}-{index:03d}.b64"
        assert part["name"] == name
        content = (stage / "parts" / name).read_bytes()
        assert sha256(content) == part["sha256"], name
        chunks.append(content)
    data = base64.b64decode(b"".join(chunks), validate=True)
    assert len(data) == record["bytes"] and sha256(data) == record["sha256"], record["name"]
    (output / record["name"]).write_bytes(data)

apk = output / apk_name
signature = subprocess.check_output(
    [tools / "apksigner", "verify", "--verbose", "--print-certs-pem", apk], text=True,
)
assert "Verified using v2 scheme (APK Signature Scheme v2): true" in signature.splitlines()
assert "Number of signers: 1" in signature.splitlines()
assert f"Signer #1 certificate SHA-256 digest: {pin}" in signature.splitlines()
pem_match = re.search(r"-----BEGIN CERTIFICATE-----\n([A-Za-z0-9+/=\n]+)-----END CERTIFICATE-----", signature)
assert pem_match
pem = pem_match.group() + "\n"
assert sha256(base64.b64decode("".join(pem_match[1].splitlines()), validate=True)) == pin
(output / "SIGNATURE.txt").write_text(signature)
(output / "signing-certificate.pem").write_text(pem)

badging = subprocess.check_output([tools / "aapt2", "dump", "badging", apk], text=True)
package = next(line for line in badging.splitlines() if line.startswith("package:"))
assert "name='com.walkmanstudio.nativeplayer'" in package
assert "versionCode='108'" in package and "versionName='1.4.0-audio-engine'" in package
assert "sdkVersion:'26'" in badging.splitlines()
assert "targetSdkVersion:'35'" in badging.splitlines()
assert "application-debuggable" not in badging.splitlines()
abi = next(line for line in badging.splitlines() if line.startswith("native-code:"))
assert set(re.findall(r"'([^']+)'", abi)) == {"arm64-v8a", "x86_64"}
subprocess.run([tools / "zipalign", "-c", "-P", "16", "4", apk], check=True)

update = manifest["update"]
assert update["schema"] == 1 and update["minSdk"] == 26
assert update["versionCode"] == 108 and update["versionName"] == manifest["versionName"]
assert update["certificateSha256"] == pin
assert update["bytes"] == apk.stat().st_size and update["sha256"] == sha256(apk.read_bytes())
assert update["url"] == f"https://github.com/Melbournejr/walkman-studio-native/releases/download/stage12/{apk_name}"
(output / "update.json").write_text(json.dumps(update, indent=2) + "\n")
checksums = [f"{sha256(path.read_bytes())}  {path.name}" for path in sorted(output.iterdir())]
(output / "SHA256SUMS").write_text("\n".join(checksums) + "\n")
