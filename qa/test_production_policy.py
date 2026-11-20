# SPDX-License-Identifier: GPL-3.0-or-later
import unittest

from production_policy import verify_production_signature


class ProductionSignatureTest(unittest.TestCase):
    def setUp(self):
        self.policy = {"certificateSha256": "a" * 64}
        self.signature = "\n".join([
            "Verified using v2 scheme (APK Signature Scheme v2): true",
            "Number of signers: 1",
            "Signer #1 certificate DN: CN=Walkman Studio Native Production",
            "Signer #1 certificate SHA-256 digest: " + "a" * 64,
            "Signer #1 key algorithm: RSA",
            "Signer #1 key size (bits): 4096",
        ])

    def test_matching_production_certificate(self):
        verify_production_signature(self.signature, self.policy)

    def test_unexpected_certificate(self):
        with self.assertRaisesRegex(ValueError, "differs"):
            verify_production_signature(self.signature, {"certificateSha256": "b" * 64})

    def test_debug_identity_rejected_even_when_pinned(self):
        debug = self.signature.replace("CN=Walkman Studio Native Production", "CN=Android Debug")
        with self.assertRaisesRegex(ValueError, "debug"):
            verify_production_signature(debug, self.policy)

    def test_weak_key_and_additional_signers_rejected(self):
        for signature in (
            self.signature.replace("4096", "2048"),
            self.signature.replace("Number of signers: 1", "Number of signers: 2"),
        ):
            with self.assertRaises(ValueError):
                verify_production_signature(signature, self.policy)
