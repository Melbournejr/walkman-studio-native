#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Prepare stage12 release assets after running the APK and source review gates."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from production_policy import read_policy

ROOT = Path(__file__).resolve().parents[1]
TAG = read_policy()["tag"]
APK_NAME = f"walkman-native-{TAG}.apk"
SOURCE_NAME = f"walkman-native-{TAG}-code.zip"

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("signed_apk", type=Path)
parser.add_argument("output", type=Path, help="New staging directory outside the checkout")
args = parser.parse_args()
if args.output.resolve().is_relative_to(ROOT):
    parser.error("Place the release staging directory outside the checkout")
if args.output.exists():
    parser.error("The staging directory must be new")
if subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=no"], cwd=ROOT):
    parser.error("Commit the release source first")

subprocess.run(
    [sys.executable, str(ROOT / "qa/check-final-apk.py"), str(args.signed_apk),
     "--stage", "12"],
    cwd=ROOT, check=True,
)
report = json.loads((ROOT / "qa/stage12-apk.json").read_text())
assets = args.output / "assets"
assets.mkdir(parents=True)
apk = assets / APK_NAME
shutil.copyfile(args.signed_apk, apk)
if hashlib.sha256(apk.read_bytes()).hexdigest() != report["sha256"]:
    sys.exit("APK changed after verification")

# Restrict the source archive to the reviewed native source, without older release payloads.
files = subprocess.check_output(
    ["git", "ls-tree", "--name-only", "-z", "HEAD"], cwd=ROOT,
).decode().strip("\0").split("\0")
files = [name for name in files if name not in ("distribution", "downloads")]
source = assets / SOURCE_NAME
subprocess.run(
    ["git", "archive", "--format=zip", "--prefix=walkman-studio-native/",
     f"--output={source.resolve()}", "HEAD", "--", *files], cwd=ROOT, check=True,
)
update = {
    "schema": 1,
    "versionCode": report["versionCode"],
    "versionName": report["versionName"],
    "minSdk": report["minSdk"],
    "bytes": report["bytes"],
    "sha256": report["sha256"],
    "url": f"https://github.com/Melbournejr/walkman-studio-native/releases/download/{TAG}/{APK_NAME}",
    "certificateSha256": report["certificateSha256"],
    "notes": "High-resolution PCM DSP, EQ transitions, and observed audio routing.",
}
(assets / "update.json").write_text(json.dumps(update, indent=2) + "\n")
# Keep the existing chunked distribution convention available for GitHub handoff.
distribution = args.output / "distribution" / TAG
parts = distribution / "parts"
parts.mkdir(parents=True)
records = []
for kind, path in (("apk", apk), ("source", source)):
    data = path.read_bytes()
    encoded = base64.b64encode(data)
    chunks = []
    for index, offset in enumerate(range(0, len(encoded), 48 * 1024)):
        name = f"{kind}-{index:03d}.b64"
        chunk = encoded[offset:offset + 48 * 1024]
        (parts / name).write_bytes(chunk)
        chunks.append({"name": name, "sha256": hashlib.sha256(chunk).hexdigest()})
    records.append({
        "name": path.name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
        "parts": chunks,
    })
sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ["ANDROID_HOME"])
signature = subprocess.check_output([
    str(sdk / "build-tools/35.0.0/apksigner"), "verify", "--verbose",
    "--print-certs-pem", str(apk),
], text=True)
pem = signature[signature.index("-----BEGIN CERTIFICATE-----"):].strip() + "\n"
(assets / "SIGNATURE.txt").write_text(signature)
(assets / "signing-certificate.pem").write_text(pem)
ready = {
    "versionCode": report["versionCode"],
    "versionName": report["versionName"],
    "nativeCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
    "certificateSha256": report["certificateSha256"],
    "certificatePem": pem,
    "signature": signature,
    "update": update,
    "files": records,
}
(distribution / "READY.json").write_text(json.dumps(ready, indent=2) + "\n")
checksums = [
    f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}"
    for path in sorted(assets.iterdir())
]
(assets / "SHA256SUMS").write_text("\n".join(checksums) + "\n")
print(f"Verified release assets prepared in {assets}")
