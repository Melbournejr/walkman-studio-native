#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Verify both first-release OTA test APKs before upload to the protected run."""
from pathlib import Path
import re
import subprocess
import sys

from production_policy import read_policy, verify_production_signature

if not __debug__:
    sys.exit("Probe verification requires Python assertions")

tools, release_apk, probe_apk = map(Path, sys.argv[1:])
policy = read_policy()
assert policy["tag"] == "stage12" and policy["versionCode"] == "108"
for path, version in ((release_apk, 108), (probe_apk, 109)):
    signature = subprocess.check_output(
        [tools / "apksigner", "verify", "--verbose", "--print-certs", path], text=True,
    )
    verify_production_signature(signature, policy)
    badging = subprocess.check_output([tools / "aapt2", "dump", "badging", path], text=True)
    package = next(line for line in badging.splitlines() if line.startswith("package:"))
    assert "name='com.walkmanstudio.nativeplayer'" in package
    assert int(re.search(r"versionCode='([0-9]+)'", package)[1]) == version
    assert "minSdkVersion:'26'" in badging.splitlines()
    if version == 109:
        assert "versionName='1.4.0-audio-engine-ota-probe'" in package
print("Production versions 108 and 109 verify with the same signer")
