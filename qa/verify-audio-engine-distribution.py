#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Verify the reviewed Stage 12 distribution independently of the signing host."""
import base64
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile
from production_policy import read_policy, verify_production_signature

if not __debug__:
    sys.exit("Distribution validation requires Python assertions to be enabled")

if len(sys.argv) not in (3, 4):
    sys.exit(f"Usage: {sys.argv[0]} BUILD_TOOLS OUTPUT [DISTRIBUTION]")

tools, output = map(Path, sys.argv[1:3])
policy = read_policy()
tag = policy["tag"]
version_code = int(policy["versionCode"])
version_name = policy["versionName"]
stage = (
    Path(sys.argv[3]) if len(sys.argv) == 4
    else Path(__file__).resolve().parents[1] / "distribution" / tag
)
apk_name = f"walkman-native-{tag}.apk"
source_name = f"walkman-native-{tag}-code.zip"
pin = policy["certificateSha256"]


def sha256(data):
    return hashlib.sha256(data).hexdigest()


manifest = json.loads((stage / "READY.json").read_text())
assert manifest["versionCode"] == version_code and manifest["versionName"] == version_name
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

source_commit = manifest["nativeCommit"]
assert re.fullmatch(r"[a-f0-9]{40}", source_commit)
subprocess.run(["git", "merge-base", "--is-ancestor", source_commit, "HEAD"], check=True)
roots = subprocess.check_output(["git", "ls-tree", "--name-only", "-z", source_commit])
paths = [name.decode() for name in roots.split(b"\0") if name and name not in (b"distribution", b"downloads")]
canonical_zip = subprocess.check_output([
    "git", "archive", "--format=zip", "--prefix=walkman-studio-native/", source_commit, "--", *paths,
])
with zipfile.ZipFile(io.BytesIO(canonical_zip)) as canonical, zipfile.ZipFile(output / source_name) as actual:
    assert actual.namelist() == canonical.namelist(), "Source archive file list differs from the reviewed commit"
    for item in canonical.infolist():
        installed = actual.getinfo(item.filename)
        assert installed.external_attr == item.external_attr, item.filename
        assert actual.read(installed) == canonical.read(item), item.filename

apk = output / apk_name
with zipfile.ZipFile(apk) as archive:
    assert any(pin.encode("ascii") + b"\0" in archive.read(name)
               for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name))

signature = subprocess.check_output(
    [tools / "apksigner", "verify", "--verbose", "--print-certs-pem", apk], text=True,
)
verify_production_signature(signature, policy)
pem_match = re.search(r"-----BEGIN CERTIFICATE-----\n([A-Za-z0-9+/=\n]+)-----END CERTIFICATE-----", signature)
assert pem_match
pem = pem_match.group() + "\n"
assert sha256(base64.b64decode("".join(pem_match[1].splitlines()), validate=True)) == pin
(output / "SIGNATURE.txt").write_text(signature)
(output / "signing-certificate.pem").write_text(pem)

badging = subprocess.check_output([tools / "aapt2", "dump", "badging", apk], text=True)
package = next(line for line in badging.splitlines() if line.startswith("package:"))
assert "name='com.walkmanstudio.nativeplayer'" in package
assert f"versionCode='{version_code}'" in package and f"versionName='{version_name}'" in package
assert "minSdkVersion:'26'" in badging.splitlines()
assert "targetSdkVersion:'35'" in badging.splitlines()
assert "application-debuggable" not in badging.splitlines()
abi = next(line for line in badging.splitlines() if line.startswith("native-code:"))
assert set(re.findall(r"'([^']+)'", abi)) == {"arm64-v8a", "x86_64"}
subprocess.run([tools / "zipalign", "-c", "-P", "16", "4", apk], check=True)

update = manifest["update"]
assert update["schema"] == 1 and update["minSdk"] == 26
assert update["versionCode"] == version_code and update["versionName"] == manifest["versionName"]
assert update["certificateSha256"] == pin
assert update["bytes"] == apk.stat().st_size and update["sha256"] == sha256(apk.read_bytes())
assert update["url"] == f"https://github.com/Melbournejr/walkman-studio-native/releases/download/{tag}/{apk_name}"
(output / "update.json").write_text(json.dumps(update, indent=2) + "\n")
checksums = [f"{sha256(path.read_bytes())}  {path.name}" for path in sorted(output.iterdir())]
(output / "SHA256SUMS").write_text("\n".join(checksums) + "\n")
