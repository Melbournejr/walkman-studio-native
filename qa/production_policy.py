# SPDX-License-Identifier: GPL-3.0-or-later
"""Read the reviewed public Android release policy. An empty pin blocks signing."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
LEGACY_CERTIFICATE = "2354b7de5b85a17540a1b7e43b0c66e49295ea353c9f17c33d54e9f42338dff2"


def verify_production_signature(signature, policy):
    lines = signature.splitlines()
    if "Number of signers: 1" not in lines or "Verified using v2 scheme (APK Signature Scheme v2): true" not in lines:
        raise ValueError("Production APK requires a single verified v2 signer")
    if f'Signer #1 certificate SHA-256 digest: {policy["certificateSha256"]}' not in lines:
        raise ValueError("APK signer differs from the reviewed production certificate")
    if any("certificate DN:" in line and "CN=Android Debug" in line for line in lines):
        raise ValueError("Android debug certificates are ineligible for production")
    if "Signer #1 key algorithm: RSA" not in lines or not any(
        line in lines for line in ("Signer #1 key size (bits): 3072", "Signer #1 key size (bits): 4096")
    ):
        raise ValueError("Production signing requires a 3072-bit or 4096-bit RSA key")


def read_policy():
    fields = {}
    for line in (ROOT / "release/production.properties").read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, value = line.split("=", 1)
        if key in fields:
            raise ValueError(f"Duplicate release policy field: {key}")
        fields[key] = value
    if not re.fullmatch(r"[0-9a-f]{64}", fields["certificateSha256"]):
        raise ValueError("Owner must commit the backed-up production certificate SHA-256")
    if fields["certificateSha256"] in (LEGACY_CERTIFICATE, "0" * 64):
        raise ValueError("A dedicated production signing certificate is required")
    if not fields["versionCode"].isascii() or not fields["versionCode"].isdigit() or not 107 < int(fields["versionCode"]) <= 2100000000:
        raise ValueError("Invalid production versionCode")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", fields["versionName"]):
        raise ValueError("Invalid release versionName")
    if not re.fullmatch(r"stage(?:12|[1-9][0-9]+)", fields["tag"]) or int(fields["tag"][5:]) < 12:
        raise ValueError("Invalid production release tag")
    return fields


if __name__ == "__main__":
    try:
        print(read_policy()[sys.argv[1]])
    except (ValueError, KeyError) as error:
        sys.exit(str(error))
