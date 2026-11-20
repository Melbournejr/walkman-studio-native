#!/usr/bin/env python3
"""Verify packaged identity/privacy flags; aapt is from Android Build Tools."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('apk', type=Path)
parser.add_argument('--aapt', required=True)
args = parser.parse_args()
badging = subprocess.check_output([args.aapt, 'dump', 'badging', str(args.apk)], text=True)
manifest = subprocess.check_output([args.aapt, 'dump', 'xmltree', str(args.apk), 'AndroidManifest.xml'], text=True)
assert "name='com.walkmanstudio.nativeplayer'" in badging
assert "sdkVersion:'26'" in badging and "targetSdkVersion:'35'" in badging
for permission in ('android.permission.INTERNET', 'android.permission.MANAGE_EXTERNAL_STORAGE',
                   'android.permission.READ_EXTERNAL_STORAGE', 'android.permission.WRITE_EXTERNAL_STORAGE',
                   'android.permission.READ_MEDIA_AUDIO'):
    assert permission not in manifest, permission
assert any('android:allowBackup' in line and '0x0' in line.split('=')[-1] for line in manifest.splitlines())
with args.apk.open('rb') as f:
    sha = hashlib.file_digest(f, 'sha256').hexdigest()
report = {'artifact': args.apk.name, 'bytes': args.apk.stat().st_size, 'sha256': sha,
          'applicationId': 'com.walkmanstudio.nativeplayer', 'minSdk': 26, 'targetSdk': 35,
          'internetPermission': False, 'broadStoragePermission': False, 'allowBackup': False,
          'notVerified': ['Physical Android install', 'Real SAF provider', 'Xiaomi HyperOS']}
print(json.dumps(report, indent=2))
