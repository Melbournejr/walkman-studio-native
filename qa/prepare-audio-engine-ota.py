#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Prepare stage12 release assets after running the APK and source review gates."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
TAG = "stage12"
APK_NAME = "walkman-native-stage12.apk"
SOURCE_NAME = "walkman-native-stage12-code.zip"

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("signed_apk", type=Path)
parser.add_argument("stage11_apk", type=Path)
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
     "--stage", "12", "--previous-apk", str(args.stage11_apk)],
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
checksums = [
    f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}"
    for path in sorted(assets.iterdir())
]
(assets / "SHA256SUMS").write_text("\n".join(checksums) + "\n")
print(f"Verified release assets prepared in {assets}")
