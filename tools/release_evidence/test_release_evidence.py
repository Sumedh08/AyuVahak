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
    load_vulnerability_report,
    release_eligibility,
    signer_fingerprint,
    _finding_has_review_disposition,
    validate_version,
    verify_provenance,
    verify_signed_bundle,
    write_signed_bundle,
)
from spdx_normalize import normalize_spdx_document
from spdx_sbom import (
    SbomGenerationError,
    _check_trace,
    _local_only_environment,
    _validate_repository_maven_settings,
)


def reviewed_vulnerability_report(*, sbom_sha256: str, artifact_sha256: str) -> dict:
    return {
        "schemaVersion": "ayuvahak.vulnerability-inventory/v1",
        "status": "REVIEWED",
        "reportSha256": "9" * 64,
        "tool": {"name": "OSV-Scanner", "version": "2.6.0"},
        "database": {
            "name": "OSV",
            "source": "https://osv-vulnerabilities.storage.googleapis.com/Maven/all.zip",
            "ecosystem": "Maven",
            "retrievedAt": "2026-10-20T08:00:00Z",
            "lastModified": "2026-10-19T19:48:00Z",
            "sha256": "7" * 64,
        },
        "scan": {
            "sbomSha256": sbom_sha256,
            "artifactSha256": artifact_sha256,
            "resultSha256": "8" * 64,
            "componentCount": 194,
            "findingCount": 0,
        },
        "findings": [],
        "review": {
            "status": "COMPLETE",
            "reviewer": "Codex local Phase 13 acceptance review",
            "reviewedAt": "2026-10-20T09:00:00Z",
            "findingsReviewed": 0,
        },
    }


class ReleaseEvidenceTests(unittest.TestCase):
    def test_vulnerability_report_loader_binds_exact_local_file_bytes(self):
        with tempfile.TemporaryDirectory(prefix="ayuvahak-vulnerability-evidence-") as tmp:
            report_path = Path(tmp) / "reviewed-inventory.json"
            report_path.write_bytes(b'{"status":"REVIEWED","findings":[]}\n')

            report = load_vulnerability_report(report_path)

        self.assertEqual(hashlib.sha256(b'{"status":"REVIEWED","findings":[]}\n').hexdigest(), report["reportSha256"])
        self.assertEqual([], report["findings"])

    def test_vulnerability_report_loader_rejects_non_object_json(self):
        with tempfile.TemporaryDirectory(prefix="ayuvahak-vulnerability-evidence-") as tmp:
            report_path = Path(tmp) / "invalid-inventory.json"
            report_path.write_text("[]\n", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "must be a JSON object"):
                load_vulnerability_report(report_path)

    def test_vulnerability_finding_review_requires_explicit_risk_dimensions(self):
        finding = {
            "id": "SYN-CVE-0005",
            "component": "org.example:synthetic",
            "version": "1.0.0",
            "severity": "critical",
            "decision": "NOT_APPLICABLE",
            "owner": "platform-security",
            "rationale": "Synthetic fixture only.",
            "reviewed": True,
        }

        self.assertFalse(_finding_has_review_disposition(finding))

    def test_spdx_generation_environment_is_offline_loopback_only_and_credential_free(self):
        with tempfile.TemporaryDirectory(prefix="ayuvahak-spdx-env-") as tmp:
            root = Path(tmp)
            (root / "mvnw.cmd").write_text("@echo off\n", encoding="utf-8")
            repository = root / "m2"
            repository.mkdir()
            scratch = root / "scratch"
            scratch.mkdir()
            prior = {
                key: os.environ.get(key)
                for key in ("SYNTHETIC_API_TOKEN", "HTTPS_PROXY", "PYTHONPATH", "NODE_OPTIONS")
            }
            os.environ["SYNTHETIC_API_TOKEN"] = "SYNTHETIC-DO-NOT-PASS"
            os.environ["HTTPS_PROXY"] = "http://SYNTHETIC-DO-NOT-PASS.invalid"
            os.environ["PYTHONPATH"] = "C:\\SYNTHETIC\\DO-NOT-PASS"
            os.environ["NODE_OPTIONS"] = "--require=SYNTHETIC-DO-NOT-PASS"
            try:
                environment, wrapper = _local_only_environment(
                    project_root=root,
                    maven_repository=repository,
                    temp_root=scratch,
                )
            finally:
                for key, value in prior.items():
                    if value is None:
                        os.environ.pop(key, None)
                    else:
                        os.environ[key] = value

            self.assertEqual("mvnw.cmd", wrapper)
            self.assertEqual("-o -Dscope=runtime", environment["MVN_ARGS"])
            self.assertEqual("localhost,127.0.0.1,::1", environment["CDXGEN_ALLOWED_HOSTS"])
            self.assertEqual("mvnw.cmd", environment["CDXGEN_ALLOWED_COMMANDS"])
            self.assertNotIn("MVNW_REPOURL", environment)
            self.assertEqual("true", environment["CDXGEN_NO_CACHE"])
            self.assertNotIn("SYNTHETIC_API_TOKEN", environment)
            self.assertNotIn("HTTPS_PROXY", environment)
            self.assertNotIn("PYTHONPATH", environment)
            self.assertNotIn("NODE_OPTIONS", environment)

    def test_spdx_generation_requires_credential_free_repository_maven_settings(self):
        with tempfile.TemporaryDirectory(prefix="ayuvahak-spdx-settings-") as tmp:
            root = Path(tmp)
            config = root / ".mvn"
            config.mkdir()
            maven_config = config / "maven.config"
            settings = config / "settings.xml"
            maven_config.write_text("--settings=.mvn/settings.xml\n", encoding="utf-8")
            settings.write_text(
                '<settings><mirrors><mirror><id>SYN-MIRROR</id></mirror></mirrors></settings>\n',
                encoding="utf-8",
            )
            _validate_repository_maven_settings(root)

            settings.write_text(
                "<settings><servers><server><id>SYN-ONLY</id></server></servers></settings>\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(SbomGenerationError, "servers, proxies, or credential fields"):
                _validate_repository_maven_settings(root)

    def test_spdx_generation_trace_rejects_external_network_activity(self):
        with tempfile.TemporaryDirectory(prefix="ayuvahak-spdx-trace-") as tmp:
            trace = Path(tmp) / "trace.jsonl"
            trace.write_text(
                '{"type":"spawn","command":"mvnw.cmd"}\n'
                '{"type":"host","host":"example.invalid","status":"blocked"}\n',
                encoding="utf-8",
            )
            with self.assertRaisesRegex(SbomGenerationError, "remote network access"):
                _check_trace(trace, "mvnw.cmd")

    def test_spdx_generation_trace_accepts_only_local_wrapper_execution(self):
        with tempfile.TemporaryDirectory(prefix="ayuvahak-spdx-trace-") as tmp:
            trace = Path(tmp) / "trace.jsonl"
            trace.write_text(
                '{"type":"phase","phase":"Generating BOM"}\n'
                '{"type":"spawn","command":"mvnw.cmd"}\n',
                encoding="utf-8",
            )
            _check_trace(trace, "mvnw.cmd")

    def test_spdx_normalization_binds_application_artifact_and_maven_license(self):
        with tempfile.TemporaryDirectory(prefix="ayuvahak-spdx-normalize-") as tmp:
            root = Path(tmp)
            pom = root / "org" / "example" / "demo" / "1.0.0" / "demo-1.0.0.pom"
            pom.parent.mkdir(parents=True)
            pom.write_text(
                """<project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion><groupId>org.example</groupId>
                <artifactId>demo</artifactId><version>1.0.0</version>
                <licenses><license><name>Apache License, Version 2.0</name>
                <url>https://www.apache.org/licenses/LICENSE-2.0</url></license></licenses>
                </project>""",
                encoding="utf-8",
            )
            package_id = "urn:synthetic:package:demo"
            application_id = "urn:synthetic:package:application"
            creation_id = "urn:synthetic:creation"
            source = {
                "@context": "https://spdx.org/rdf/3.0.1/spdx-context.jsonld",
                "@graph": [
                    {
                        "type": "SpdxDocument",
                        "spdxId": "urn:synthetic:document",
                        "profileConformance": ["extension"],
                    },
                    {"type": "CreationInfo", "@id": creation_id,
                     "createdBy": ["https://github.com/cdxgen/cdxgen"]},
                    {"type": "software_Package", "spdxId": package_id,
                     "creationInfo": creation_id, "name": "demo",
                     "software_packageVersion": "1.0.0",
                     "software_packageUrl": "pkg:maven/org.example/demo@1.0.0"},
                    {"type": "software_Package", "spdxId": application_id,
                     "creationInfo": creation_id, "name": "AyuVahak",
                     "software_packageUrl": "pkg:application/AyuVahak@1.0.0"},
                ],
            }

            result = normalize_spdx_document(
                source,
                artifact_sha256="a" * 64,
                application_purl="pkg:maven/org.example/demo@1.0.0",
                maven_repository=root,
            )

            packages = [item for item in result["@graph"] if item.get("type") == "software_Package"]
            package = next(item for item in packages if item["spdxId"] == package_id)
            self.assertIn({"type": "Hash", "algorithm": "sha256", "hashValue": "a" * 64},
                          package["verifiedUsing"])
            self.assertNotIn("verifiedUsing", next(item for item in packages if item["spdxId"] == application_id))
            licenses = [item for item in result["@graph"]
                        if item.get("type") == "simplelicensing_LicenseExpression"]
            self.assertEqual(["Apache-2.0"], [item["simplelicensing_licenseExpression"] for item in licenses])
            self.assertTrue(any(item.get("relationshipType") == "hasDeclaredLicense"
                                and item.get("from") == package_id
                                and item.get("to") == [licenses[0]["spdxId"]]
                                for item in result["@graph"]))
            document = next(item for item in result["@graph"] if item.get("type") == "SpdxDocument")
            self.assertNotIn("profileConformance", document)
            self.assertIn({"type": "ExternalMap", "externalSpdxId": "https://github.com/cdxgen/cdxgen"},
                          document["import"])

    def test_spdx_normalization_does_not_guess_ambiguous_license(self):
        with tempfile.TemporaryDirectory(prefix="ayuvahak-spdx-ambiguous-") as tmp:
            root = Path(tmp)
            pom = root / "org" / "example" / "demo" / "1.0.0" / "demo-1.0.0.pom"
            pom.parent.mkdir(parents=True)
            pom.write_text(
                """<project xmlns="http://maven.apache.org/POM/4.0.0">
                <licenses><license><name>LGPL 2.1</name>
                <url>https://www.gnu.org/licenses/lgpl-2.1.html</url></license></licenses>
                </project>""",
                encoding="utf-8",
            )
            creation_id = "urn:synthetic:creation"
            source = {"@graph": [
                {"type": "SpdxDocument", "spdxId": "urn:synthetic:document"},
                {"type": "CreationInfo", "@id": creation_id,
                 "createdBy": ["https://github.com/cdxgen/cdxgen"]},
                {"type": "software_Package", "spdxId": "urn:synthetic:package",
                 "creationInfo": creation_id, "name": "demo",
                 "software_packageVersion": "1.0.0",
                 "software_packageUrl": "pkg:maven/org.example/demo@1.0.0"},
            ]}

            result = normalize_spdx_document(
                source,
                artifact_sha256="b" * 64,
                application_purl="pkg:maven/org.example/demo@1.0.0",
                maven_repository=root,
            )

            self.assertFalse(any(item.get("type") == "simplelicensing_LicenseExpression"
                                 for item in result["@graph"]))

    def test_spdx_normalization_is_idempotent(self):
        creation_id = "urn:synthetic:creation"
        source = {"@graph": [
            {"type": "SpdxDocument", "spdxId": "urn:synthetic:document"},
            {"type": "CreationInfo", "@id": creation_id,
             "createdBy": ["https://github.com/cdxgen/cdxgen"]},
            {"type": "software_Package", "spdxId": "urn:synthetic:package",
             "creationInfo": creation_id, "name": "demo",
             "software_packageVersion": "1.0.0",
             "software_packageUrl": "pkg:maven/org.example/demo@1.0.0"},
        ]}

        first = normalize_spdx_document(
            source, artifact_sha256="c" * 64,
            application_purl="pkg:maven/org.example/demo@1.0.0",
            maven_repository=Path("not-present"),
        )
        second = normalize_spdx_document(
            first, artifact_sha256="c" * 64,
            application_purl="pkg:maven/org.example/demo@1.0.0",
            maven_repository=Path("not-present"),
        )

        self.assertEqual(first, second)

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
                "vulnerabilityReport": reviewed_vulnerability_report(
                    sbom_sha256="a" * 64, artifact_sha256="1" * 64
                ),
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
        self.assertIn("validated SPDX 3.0 SBOM evidence is required", claimed_valid["blockers"])

    def test_release_eligibility_accepts_complete_bound_local_evidence(self):
        digest = "a" * 64
        schema_digest = "b" * 64
        model_digest = "c" * 64
        context_digest = "d" * 64
        license_list_digest = "f" * 64
        report = {
            "result": "PASS",
            "format": "SPDX-3.0.1",
            "sbomSha256": digest,
            "schema": {"sha256": schema_digest},
            "shacl": {"sha256": model_digest},
            "contextSha256": context_digest,
            "licenseList": {"sha256": license_list_digest},
        }
        vulnerability_report = reviewed_vulnerability_report(
            sbom_sha256=digest, artifact_sha256="1" * 64
        )
        result = release_eligibility(
            {
                "sbom": {
                    "format": "SPDX-3.0.1",
                    "status": "VALIDATED",
                    "validator": "offline JSON Schema + SHACL validator",
                    "validationProfile": "official SPDX 3.0.1 JSON Schema + model",
                    "sha256": digest,
                    "schemaSha256": schema_digest,
                    "modelSha256": model_digest,
                    "contextSha256": context_digest,
                    "licenseListSha256": license_list_digest,
                    "validationReportSha256": "e" * 64,
                    "validationReport": report,
                    "applicationArtifactSha256": "1" * 64,
                    "runtimePackageCount": 195,
                    "runtimeDependencyCount": 194,
                },
                "provenance": {"status": "VERIFIED"},
                "signature": {"status": "VERIFIED"},
                "vulnerabilityReport": vulnerability_report,
            }
        )

        self.assertTrue(result["eligible"])
        self.assertEqual([], result["blockers"])

    def test_release_eligibility_rejects_inventory_bound_to_other_jar(self):
        digest = "a" * 64
        schema_digest = "b" * 64
        model_digest = "c" * 64
        context_digest = "d" * 64
        license_list_digest = "f" * 64
        report = {
            "result": "PASS",
            "format": "SPDX-3.0.1",
            "sbomSha256": digest,
            "schema": {"sha256": schema_digest},
            "shacl": {"sha256": model_digest},
            "contextSha256": context_digest,
            "licenseList": {"sha256": license_list_digest},
        }
        vulnerability_report = reviewed_vulnerability_report(
            sbom_sha256=digest, artifact_sha256="2" * 64
        )
        result = release_eligibility(
            {
                "sbom": {
                    "format": "SPDX-3.0.1",
                    "status": "VALIDATED",
                    "validator": "offline JSON Schema + SHACL validator",
                    "validationProfile": "official SPDX 3.0.1 JSON Schema + model",
                    "sha256": digest,
                    "schemaSha256": schema_digest,
                    "modelSha256": model_digest,
                    "contextSha256": context_digest,
                    "licenseListSha256": license_list_digest,
                    "validationReportSha256": "e" * 64,
                    "validationReport": report,
                    "applicationArtifactSha256": "1" * 64,
                    "runtimePackageCount": 195,
                    "runtimeDependencyCount": 194,
                },
                "provenance": {"status": "VERIFIED"},
                "signature": {"status": "VERIFIED"},
                "vulnerabilityReport": vulnerability_report,
            }
        )
        self.assertFalse(result["eligible"])
        self.assertIn("reviewed local vulnerability inventory is required", result["blockers"])

    def test_release_eligibility_rejects_scan_component_count_mismatch(self):
        digest = "a" * 64
        schema_digest = "b" * 64
        model_digest = "c" * 64
        context_digest = "d" * 64
        license_list_digest = "f" * 64
        validation_report = {
            "result": "PASS",
            "format": "SPDX-3.0.1",
            "sbomSha256": digest,
            "schema": {"sha256": schema_digest},
            "shacl": {"sha256": model_digest},
            "contextSha256": context_digest,
            "licenseList": {"sha256": license_list_digest},
        }
        vulnerability_report = reviewed_vulnerability_report(
            sbom_sha256=digest, artifact_sha256="1" * 64
        )
        vulnerability_report["scan"]["componentCount"] = 195
        result = release_eligibility(
            {
                "sbom": {
                    "format": "SPDX-3.0.1",
                    "status": "VALIDATED",
                    "validator": "offline JSON Schema + SHACL validator",
                    "validationProfile": "official SPDX 3.0.1 JSON Schema + model",
                    "sha256": digest,
                    "schemaSha256": schema_digest,
                    "modelSha256": model_digest,
                    "contextSha256": context_digest,
                    "licenseListSha256": license_list_digest,
                    "validationReportSha256": "e" * 64,
                    "validationReport": validation_report,
                    "applicationArtifactSha256": "1" * 64,
                    "runtimePackageCount": 195,
                    "runtimeDependencyCount": 194,
                },
                "provenance": {"status": "VERIFIED"},
                "signature": {"status": "VERIFIED"},
                "vulnerabilityReport": vulnerability_report,
            }
        )

        self.assertFalse(result["eligible"])
        self.assertIn("reviewed local vulnerability inventory is required", result["blockers"])

    def test_release_eligibility_rejects_status_only_vulnerability_claim(self):
        result = release_eligibility(
            {
                "sbom": {
                    "sha256": "a" * 64,
                    "applicationArtifactSha256": "1" * 64,
                },
                "provenance": {"status": "VERIFIED"},
                "signature": {"status": "VERIFIED"},
                "vulnerabilityReport": {"status": "REVIEWED"},
            }
        )
        self.assertFalse(result["eligible"])
        self.assertIn("reviewed local vulnerability inventory is required", result["blockers"])

    def test_release_eligibility_rejects_mismatched_spdx_asset_digest(self):
        digest = "a" * 64
        report = {
            "result": "PASS",
            "format": "SPDX-3.0.1",
            "sbomSha256": digest,
            "schema": {"sha256": "b" * 64},
            "shacl": {"sha256": "c" * 64},
            "contextSha256": "d" * 64,
            "licenseList": {"sha256": "f" * 64},
        }
        result = release_eligibility(
            {
                "sbom": {
                    "format": "SPDX-3.0.1",
                    "status": "VALIDATED",
                    "validator": "offline JSON Schema + SHACL validator",
                    "validationProfile": "official SPDX 3.0.1 JSON Schema + model",
                    "sha256": digest,
                    "schemaSha256": "0" * 64,
                    "modelSha256": "c" * 64,
                    "contextSha256": "d" * 64,
                    "licenseListSha256": "f" * 64,
                    "validationReportSha256": "e" * 64,
                    "validationReport": report,
                },
                "provenance": {"status": "VERIFIED"},
                "signature": {"status": "VERIFIED"},
                "vulnerabilityReport": {"status": "REVIEWED"},
            }
        )
        self.assertFalse(result["eligible"])
        self.assertIn("validated SPDX 3.0 SBOM evidence is required", result["blockers"])

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
