import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from datetime import datetime, timezone
from pathlib import Path

from release_evidence import (
    assess_finding,
    build_provenance,
    build_incomplete_manifest,
    release_eligibility,
    signer_fingerprint,
    validate_version,
    verify_provenance,
    verify_signed_bundle,
    write_signed_bundle,
)


class ReleaseEvidenceTests(unittest.TestCase):
    def test_semver_release_identity_rejects_maven_snapshot(self):
        validate_version("1.2.3")
        validate_version("1.2.3-rc.1")
        with self.assertRaisesRegex(ValueError, "SNAPSHOT"):
            validate_version("1.2.3-SNAPSHOT")
        with self.assertRaises(ValueError):
            validate_version("01.2.3")

    def test_provenance_binds_source_builder_parameters_and_artifact_digest(self):
        expected = hashlib.sha256(b"synthetic artifact").hexdigest()
        provenance = build_provenance(
            source_revision="a" * 40,
            builder_id="local://SYN-BUILDER-001",
            build_type="maven-package",
            parameters={"releaseVersion": "1.2.3", "migrationRange": "V1-V99"},
            artifacts={"ayuvahak.jar": expected},
        )

        self.assertEqual("a" * 40, provenance["source"]["revision"])
        self.assertEqual("local://SYN-BUILDER-001", provenance["builder"]["id"])
        self.assertEqual(expected, provenance["outputs"][0]["sha256"])
        self.assertEqual("1.2.3", provenance["buildParameters"]["releaseVersion"])
        self.assertTrue(
            verify_provenance(
                provenance,
                expected_source_revision="a" * 40,
                expected_builder_id="local://SYN-BUILDER-001",
                expected_outputs={"ayuvahak.jar": expected},
            )
        )
        self.assertFalse(
            verify_provenance(
                provenance,
                expected_source_revision="b" * 40,
                expected_builder_id="local://SYN-BUILDER-001",
                expected_outputs={"ayuvahak.jar": expected},
            )
        )

    def test_release_eligibility_fails_closed_without_validated_spdx_3(self):
        result = release_eligibility(
            {
                "sbom": {"requiredFormat": "SPDX-3.0", "status": "BLOCKED"},
                "provenance": {"status": "VERIFIED"},
                "signature": {"status": "VERIFIED"},
                "vulnerabilityReport": {"status": "REVIEWED"},
            }
        )

        self.assertFalse(result["eligible"])
        self.assertIn("validated SPDX 3.0 SBOM", result["blockers"][0])
        claimed_valid = release_eligibility(
            {
                "sbom": {
                    "format": "SPDX-3.0",
                    "status": "VALIDATED",
                    "validator": "SYN-VALIDATOR-001",
                    "validationProfile": "synthetic-profile",
                    "sha256": "a" * 64,
                },
                "provenance": {"status": "VERIFIED"},
                "signature": {"status": "VERIFIED"},
                "vulnerabilityReport": {"status": "REVIEWED"},
            }
        )
        self.assertFalse(claimed_valid["eligible"])
        self.assertTrue(any("not implemented or configured" in blocker for blocker in claimed_valid["blockers"]))

    def test_manifest_records_blocked_spdx_gate_without_claiming_release_eligibility(self):
        manifest = build_incomplete_manifest(
            version="1.2.3-rc.1",
            source_revision="a" * 40,
            builder_id="local://SYN-BUILDER-001",
            build_type="maven-package",
            build_parameters={"java": "25"},
            artifacts={"ayuvahak.jar": hashlib.sha256(b"synthetic artifact").hexdigest()},
            migration_range="V1-V99",
            supported_upgrade_path="0.1.x to 1.2.3",
            generated_at=datetime(2026, 10, 20, tzinfo=timezone.utc),
        )

        self.assertEqual("BLOCKED_NOT_GENERATED_OR_VALIDATED", manifest["sbom"]["status"])
        self.assertFalse(manifest["releaseEligibility"]["eligible"])
        self.assertEqual("1.2.3-rc.1", manifest["version"])

    def test_known_exploited_finding_requires_immediate_remediation_review(self):
        result = assess_finding(
            {
                "id": "SYN-CVE-0001",
                "severity": "low",
                "reachable": False,
                "knownExploited": True,
                "fixAvailable": True,
                "exposure": "intranet",
            }
        )

        self.assertEqual("IMMEDIATE_REMEDIATION_REVIEW", result["disposition"])
        self.assertTrue(result["releaseReviewRequired"])

    def test_missing_reachability_evidence_does_not_auto_clear_high_finding(self):
        result = assess_finding(
            {
                "id": "SYN-CVE-0002",
                "severity": "high",
                "reachable": None,
                "knownExploited": False,
                "fixAvailable": False,
                "exposure": "unknown",
            }
        )

        self.assertEqual("CONTEXTUAL_REVIEW_REQUIRED", result["disposition"])
        self.assertTrue(result["releaseReviewRequired"])

    def test_expired_vulnerability_exception_is_not_accepted(self):
        with self.assertRaisesRegex(ValueError, "expired"):
            assess_finding(
                {
                    "id": "SYN-CVE-0003",
                    "severity": "medium",
                    "reachable": False,
                    "knownExploited": False,
                    "fixAvailable": False,
                    "exposure": "local",
                },
                exception={
                    "findingId": "SYN-CVE-0003",
                    "owner": "synthetic-release-owner",
                    "rationale": "Synthetic test exception",
                    "compensatingControls": ["local-only fixture"],
                    "approvedBy": "synthetic-approver",
                    "expiresAt": "2026-10-19T00:00:00Z",
                },
                now=datetime(2026, 10, 20, tzinfo=timezone.utc),
            )

    def test_vulnerability_assessment_command_processes_local_synthetic_inventory(self):
        with tempfile.TemporaryDirectory(prefix="ayuvahak-vuln-assessment-") as tmp:
            root = Path(tmp)
            source = root / "findings.json"
            output = root / "assessment.json"
            source.write_text(
                json.dumps(
                    {
                        "findings": [
                            {
                                "id": "SYN-CVE-0004",
                                "severity": "high",
                                "reachable": True,
                                "knownExploited": False,
                                "fixAvailable": True,
                                "exposure": "public",
                            }
                        ],
                        "exceptions": [],
                    }
                ),
                encoding="utf-8",
            )
            script = Path(__file__).with_name("release_evidence.py")
            result = subprocess.run(
                [
                    sys.executable,
                    str(script),
                    "assess",
                    "--input",
                    str(source),
                    "--output",
                    str(output),
                    "--as-of",
                    "2026-10-20T00:00:00Z",
                ],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            assessment = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual("PRIORITIZED_REMEDIATION", assessment["findings"][0]["disposition"])
            self.assertTrue(assessment["findings"][0]["releaseReviewRequired"])


class SignedBundleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.keytool = shutil.which("keytool")
        cls.jarsigner = shutil.which("jarsigner")
        if not cls.keytool or not cls.jarsigner:
            raise unittest.SkipTest("local JDK keytool and jarsigner are required")
        cls.temporary = tempfile.TemporaryDirectory(prefix="ayuvahak-release-signing-")
        cls.root = Path(cls.temporary.name)
        cls.alias = "synthetic-local-test"
        cls.keystore = cls.root / "synthetic-test.p12"
        cls.environment = os.environ.copy()
        cls.environment["SYNTHETIC_KEYSTORE_PASSWORD"] = "synthetic-local-test-password"
        subprocess.run(
            [
                cls.keytool,
                "-genkeypair",
                "-alias",
                cls.alias,
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-storetype",
                "PKCS12",
                "-keystore",
                str(cls.keystore),
                "-storepass:env",
                "SYNTHETIC_KEYSTORE_PASSWORD",
                "-keypass:env",
                "SYNTHETIC_KEYSTORE_PASSWORD",
                "-dname",
                "CN=synthetic-local-test, OU=SYNTHETIC-LOCAL-TEST",
                "-validity",
                "2",
                "-noprompt",
            ],
            check=True,
            capture_output=True,
            text=True,
            env=cls.environment,
        )

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_local_signature_verifies_and_tampering_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="ayuvahak-release-evidence-") as tmp:
            root = Path(tmp)
            keystore = self.keystore
            alias = self.alias
            environment = self.environment
            artifact = root / "synthetic-artifact.jar"
            artifact.write_bytes(b"synthetic build artifact\n")
            manifest = root / "manifest.json"
            manifest.write_text('{"schemaVersion":"synthetic-test-only"}\n', encoding="utf-8")
            signed = root / "signed.jar"
            script = Path(__file__).with_name("release_evidence.py")
            sign_result = subprocess.run(
                [
                    sys.executable,
                    str(script),
                    "sign-bundle",
                    "--output",
                    str(signed),
                    "--keystore",
                    str(keystore),
                    "--alias",
                    alias,
                    "--password-env",
                    "SYNTHETIC_KEYSTORE_PASSWORD",
                    "--file",
                    f"artifact.jar={artifact}",
                    "--file",
                    f"manifest.json={manifest}",
                ],
                check=False,
                capture_output=True,
                text=True,
                env=environment,
            )
            self.assertEqual(0, sign_result.returncode, sign_result.stderr)

            self.assertTrue(signed.is_file())
            expected_signer = signer_fingerprint(self.keytool, signed)
            self.assertIsNotNone(expected_signer)
            verify_result = subprocess.run(
                [
                    sys.executable,
                    str(script),
                    "verify-bundle",
                    "--bundle",
                    str(signed),
                    "--signer-sha256",
                    expected_signer,
                    "--entry",
                    "artifact.jar",
                    "--entry",
                    "manifest.json",
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, verify_result.returncode, verify_result.stdout + verify_result.stderr)

            tampered = root / "tampered.jar"
            with zipfile.ZipFile(signed) as original, zipfile.ZipFile(tampered, "w") as changed:
                for entry in original.infolist():
                    content = original.read(entry.filename)
                    if entry.filename == "artifact.jar":
                        content += b"tampered"
                    changed.writestr(entry, content)

            tamper_result = subprocess.run(
                [
                    sys.executable,
                    str(script),
                    "verify-bundle",
                    "--bundle",
                    str(tampered),
                    "--signer-sha256",
                    expected_signer,
                    "--entry",
                    "artifact.jar",
                    "--entry",
                    "manifest.json",
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(2, tamper_result.returncode)

    def test_wrong_expected_signer_identity_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="ayuvahak-release-signer-") as tmp:
            root = Path(tmp)
            artifact = root / "synthetic-artifact.jar"
            artifact.write_bytes(b"synthetic build artifact\n")
            signed = root / "wrong-signer-test.jar"
            write_signed_bundle(
                {"artifact.jar": artifact},
                signed,
                self.keystore,
                self.alias,
                "SYNTHETIC_KEYSTORE_PASSWORD",
                self.environment,
                self.jarsigner,
            )
            self.assertFalse(
                verify_signed_bundle(
                    signed,
                    "00" * 32,
                    self.keytool,
                    self.jarsigner,
                )
            )


if __name__ == "__main__":
    unittest.main()
