#!/usr/bin/env python3
"""Local-only helpers for preparing and checking AyuVahak release evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import ssl
import subprocess
import sys
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any

from spdx_sbom import SbomGenerationError, generate_and_validate_spdx, sha256_file


MANIFEST_SCHEMA = "ayuvahak.release-evidence/v1"
PROVENANCE_SCHEMA = "ayuvahak.release-provenance/v1"
VULNERABILITY_REPORT_SCHEMA = "ayuvahak.vulnerability-inventory/v1"
SEMVER_PATTERN = re.compile(
    r"^(0|[1-9][0-9]*)\."
    r"(0|[1-9][0-9]*)\."
    r"(0|[1-9][0-9]*)"
    r"(?:-((?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
    r"(?:\.(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)
REVISION_PATTERN = re.compile(r"^(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$")
PEM_CERT_PATTERN = re.compile(
    r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", re.DOTALL
)


class ReleaseEvidenceError(ValueError):
    """Raised when local release evidence is malformed or unsafe to accept."""


def validate_version(version: str) -> None:
    """Validate a SemVer 2.0 identity and reject Maven SNAPSHOT identities."""
    match = SEMVER_PATTERN.fullmatch(version or "")
    if match is None:
        raise ReleaseEvidenceError("version must be a valid SemVer 2.0.0 identity")
    prerelease = match.group(4)
    if prerelease and any(part.casefold() == "snapshot" for part in prerelease.split(".")):
        raise ReleaseEvidenceError("SNAPSHOT builds are not release identities")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_vulnerability_report(
    path: Path,
    *,
    database_path: Path | None = None,
    scan_result_path: Path | None = None,
    scanned_sbom_path: Path | None = None,
) -> dict[str, Any]:
    """Load a reviewed inventory and verify the exact local bytes used by the scan."""
    try:
        source_bytes = path.read_bytes()
        report = json.loads(source_bytes)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ReleaseEvidenceError("vulnerability report is unreadable or not valid JSON") from error
    if not isinstance(report, dict):
        raise ReleaseEvidenceError("vulnerability report must be a JSON object")
    provided_inputs = (database_path, scan_result_path, scanned_sbom_path)
    if any(value is not None for value in provided_inputs) and any(
        value is None for value in provided_inputs
    ):
        raise ReleaseEvidenceError(
            "vulnerability database, scan result, and scanned SBOM must be supplied together"
        )
    database = report.get("database")
    scan = report.get("scan")
    if not isinstance(database, dict) or not isinstance(scan, dict):
        raise ReleaseEvidenceError(
            "vulnerability report must identify its database and scan result"
        )
    input_verification: dict[str, str] = {"status": "UNVERIFIED"}
    if (
        database_path is not None
        and scan_result_path is not None
        and scanned_sbom_path is not None
    ):
        try:
            database_digest = sha256_file(database_path)
            scan_result_digest = sha256_file(scan_result_path)
            scanned_sbom_digest = sha256_file(scanned_sbom_path)
        except OSError as error:
            raise ReleaseEvidenceError(
                "vulnerability database, scan result, or scanned SBOM is unreadable"
            ) from error
        expected_database_digest = database.get("sha256")
        expected_scan_digest = scan.get("resultSha256")
        expected_scanned_sbom_digest = scan.get("sbomSha256")
        if database_digest != expected_database_digest:
            raise ReleaseEvidenceError(
                "vulnerability database digest does not match reviewed inventory"
            )
        if scan_result_digest != expected_scan_digest:
            raise ReleaseEvidenceError(
                "vulnerability scan result digest does not match reviewed inventory"
            )
        if scanned_sbom_digest != expected_scanned_sbom_digest:
            raise ReleaseEvidenceError(
                "scanned SBOM digest does not match reviewed inventory"
            )
        input_verification = {
            "status": "VERIFIED",
            "databaseSha256": database_digest,
            "scanResultSha256": scan_result_digest,
            "scannedSbomSha256": scanned_sbom_digest,
        }
    # Never trust an inventory's self-asserted verification field. It is
    # replaced only after the corresponding local bytes have been hashed.
    return {
        **report,
        "reportSha256": sha256_bytes(source_bytes),
        "inputVerification": input_verification,
    }


def _validate_revision(revision: str) -> None:
    if not REVISION_PATTERN.fullmatch(revision or ""):
        raise ReleaseEvidenceError("source revision must be a full 40- or 64-character Git object ID")


def build_provenance(
    *,
    source_revision: str,
    builder_id: str,
    build_type: str,
    parameters: dict[str, str],
    artifacts: dict[str, str],
    generated_at: datetime | None = None,
) -> dict[str, Any]:
    """Build signed-profile provenance; this does not assert SLSA conformance."""
    _validate_revision(source_revision)
    if not builder_id.strip() or not build_type.strip():
        raise ReleaseEvidenceError("builder identity and build type are required")
    if not artifacts:
        raise ReleaseEvidenceError("at least one output artifact is required")
    for name, digest in artifacts.items():
        _validate_archive_name(name)
        if not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ReleaseEvidenceError(f"artifact {name!r} has no valid SHA-256 digest")
    timestamp = generated_at or datetime.now(timezone.utc)
    if timestamp.tzinfo is None:
        raise ReleaseEvidenceError("provenance time must include a timezone")
    return {
        "schemaVersion": PROVENANCE_SCHEMA,
        "claim": "SLSA-informed provenance; no SLSA level or conformance claimed",
        "source": {"revision": source_revision.lower()},
        "builder": {"id": builder_id},
        "buildType": build_type,
        "buildParameters": dict(sorted(parameters.items())),
        "outputs": [
            {"name": name, "sha256": digest}
            for name, digest in sorted(artifacts.items())
        ],
        "generatedAt": timestamp.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
    }


def verify_provenance(
    provenance: dict[str, Any],
    *,
    expected_source_revision: str,
    expected_builder_id: str,
    expected_outputs: dict[str, str],
) -> bool:
    """Check provenance bindings without making a SLSA-level assertion."""
    try:
        _validate_revision(expected_source_revision)
        return (
            provenance.get("schemaVersion") == PROVENANCE_SCHEMA
            and provenance.get("claim")
            == "SLSA-informed provenance; no SLSA level or conformance claimed"
            and provenance.get("source", {}).get("revision")
            == expected_source_revision.lower()
            and provenance.get("builder", {}).get("id") == expected_builder_id
            and bool(provenance.get("buildType"))
            and isinstance(provenance.get("buildParameters"), dict)
            and provenance.get("outputs")
            == [
                {"name": name, "sha256": digest}
                for name, digest in sorted(expected_outputs.items())
            ]
            and bool(provenance.get("generatedAt"))
        )
    except (AttributeError, ReleaseEvidenceError, TypeError):
        return False


def build_incomplete_manifest(
    *,
    version: str,
    source_revision: str,
    builder_id: str,
    build_type: str,
    build_parameters: dict[str, str],
    artifacts: dict[str, str],
    migration_range: str,
    supported_upgrade_path: str,
    generated_at: datetime | None = None,
) -> dict[str, Any]:
    """Create a truthful manifest while the mandatory SPDX 3.0 gate is blocked."""
    validate_version(version)
    _validate_revision(source_revision)
    if not migration_range.strip() or not supported_upgrade_path.strip():
        raise ReleaseEvidenceError("migration range and supported upgrade path are required")
    provenance = build_provenance(
        source_revision=source_revision,
        builder_id=builder_id,
        build_type=build_type,
        parameters=build_parameters,
        artifacts=artifacts,
        generated_at=generated_at,
    )
    sbom = {
        "requiredFormat": "SPDX-3.0",
        "status": "BLOCKED_NOT_GENERATED_OR_VALIDATED",
    }
    eligibility = release_eligibility(
        {
            "sbom": sbom,
            "provenance": {"status": "PRESENT"},
            "signature": {"status": "PENDING"},
            "vulnerabilityReport": {"status": "PENDING"},
        }
    )
    return {
        "schemaVersion": MANIFEST_SCHEMA,
        "product": "AyuVahak",
        "version": version,
        "sourceRevision": source_revision.lower(),
        "builderId": builder_id,
        "buildType": build_type,
        "buildParameters": dict(sorted(build_parameters.items())),
        "artifacts": [
            {"name": name, "sha256": digest}
            for name, digest in sorted(artifacts.items())
        ],
        "migrationRange": migration_range,
        "supportedUpgradePath": supported_upgrade_path,
        "sbom": sbom,
        "provenance": provenance,
        "vulnerabilityReport": {"status": "PENDING"},
        "releaseEligibility": eligibility,
        "generatedAt": (generated_at or datetime.now(timezone.utc))
        .astimezone(timezone.utc)
        .isoformat()
        .replace("+00:00", "Z"),
    }


def build_manifest(
    *,
    version: str,
    source_revision: str,
    builder_id: str,
    build_type: str,
    build_parameters: dict[str, str],
    artifacts: dict[str, str],
    migration_range: str,
    supported_upgrade_path: str,
    sbom: dict[str, Any],
    vulnerability_report: dict[str, Any] | None = None,
    generated_at: datetime | None = None,
) -> dict[str, Any]:
    """Create a manifest bound to validated SPDX evidence and output digests."""
    validate_version(version)
    _validate_revision(source_revision)
    if not migration_range.strip() or not supported_upgrade_path.strip():
        raise ReleaseEvidenceError("migration range and supported upgrade path are required")
    if not artifacts:
        raise ReleaseEvidenceError("at least one output artifact is required")
    for name, digest in artifacts.items():
        _validate_archive_name(name)
        if not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ReleaseEvidenceError(f"artifact {name!r} has no valid SHA-256 digest")
    provenance = build_provenance(
        source_revision=source_revision,
        builder_id=builder_id,
        build_type=build_type,
        parameters=build_parameters,
        artifacts=artifacts,
        generated_at=generated_at,
    )
    vulnerability_evidence = vulnerability_report or {"status": "PENDING"}
    eligibility = release_eligibility(
        {
            "sbom": sbom,
            "provenance": {"status": "PRESENT"},
            "signature": {"status": "PENDING"},
            "vulnerabilityReport": vulnerability_evidence,
        }
    )
    return {
        "schemaVersion": MANIFEST_SCHEMA,
        "product": "AyuVahak",
        "version": version,
        "sourceRevision": source_revision.lower(),
        "builderId": builder_id,
        "buildType": build_type,
        "buildParameters": dict(sorted(build_parameters.items())),
        "artifacts": [
            {"name": name, "sha256": digest}
            for name, digest in sorted(artifacts.items())
        ],
        "migrationRange": migration_range,
        "supportedUpgradePath": supported_upgrade_path,
        "sbom": sbom,
        "provenance": provenance,
        "vulnerabilityReport": vulnerability_evidence,
        "releaseEligibility": eligibility,
        "generatedAt": (generated_at or datetime.now(timezone.utc))
        .astimezone(timezone.utc)
        .isoformat()
        .replace("+00:00", "Z"),
    }


def release_eligibility(evidence: dict[str, Any]) -> dict[str, Any]:
    """Apply fail-closed prerequisites from validated local release evidence."""
    blockers: list[str] = []
    sbom = evidence.get("sbom") or {}
    validation_report = sbom.get("validationReport")
    schema = (validation_report or {}).get("schema") or {}
    shacl = (validation_report or {}).get("shacl") or {}
    license_list = (validation_report or {}).get("licenseList") or {}
    if (
        sbom.get("status") != "VALIDATED"
        or sbom.get("format") != "SPDX-3.0.1"
        or not sbom.get("validator")
        or not sbom.get("validationProfile")
        or not re.fullmatch(r"[0-9a-f]{64}", str(sbom.get("sha256", "")))
        or not re.fullmatch(r"[0-9a-f]{64}", str(sbom.get("schemaSha256", "")))
        or not re.fullmatch(r"[0-9a-f]{64}", str(sbom.get("modelSha256", "")))
        or not re.fullmatch(r"[0-9a-f]{64}", str(sbom.get("contextSha256", "")))
        or not re.fullmatch(r"[0-9a-f]{64}", str(sbom.get("licenseListSha256", "")))
        or not isinstance(sbom.get("validationReportSha256"), str)
        or not re.fullmatch(r"[0-9a-f]{64}", str(sbom.get("validationReportSha256", "")))
        or not isinstance(validation_report, dict)
        or validation_report.get("result") != "PASS"
        or validation_report.get("format") != "SPDX-3.0.1"
        or validation_report.get("sbomSha256") != sbom.get("sha256")
        or schema.get("sha256") != sbom.get("schemaSha256")
        or shacl.get("sha256") != sbom.get("modelSha256")
        or validation_report.get("contextSha256") != sbom.get("contextSha256")
        or license_list.get("sha256") != sbom.get("licenseListSha256")
    ):
        blockers.append("validated SPDX 3.0 SBOM evidence is required")
    if (evidence.get("provenance") or {}).get("status") != "VERIFIED":
        blockers.append("verified source/build/artifact provenance is required")
    if (evidence.get("signature") or {}).get("status") != "VERIFIED":
        blockers.append("verified signature and expected signer identity are required")
    vulnerability_report = evidence.get("vulnerabilityReport") or {}
    vulnerability_scan = vulnerability_report.get("scan") or {}
    vulnerability_database = vulnerability_report.get("database") or {}
    vulnerability_tool = vulnerability_report.get("tool") or {}
    vulnerability_review = vulnerability_report.get("review") or {}
    input_verification = vulnerability_report.get("inputVerification") or {}
    findings = vulnerability_report.get("findings")
    artifact_digest = sbom.get("applicationArtifactSha256")
    component_count = vulnerability_scan.get("componentCount")
    finding_count = vulnerability_scan.get("findingCount")
    vulnerability_report_valid = (
        vulnerability_report.get("schemaVersion") == VULNERABILITY_REPORT_SCHEMA
        and vulnerability_report.get("status") == "REVIEWED"
        and re.fullmatch(r"[0-9a-f]{64}", str(vulnerability_report.get("reportSha256", "")))
        and bool(vulnerability_tool.get("name"))
        and bool(vulnerability_tool.get("version"))
        and bool(vulnerability_database.get("name"))
        and bool(vulnerability_database.get("source"))
        and bool(vulnerability_database.get("ecosystem"))
        and _is_timezone_instant(vulnerability_database.get("retrievedAt"))
        and _is_timezone_instant(vulnerability_database.get("lastModified"))
        and re.fullmatch(r"[0-9a-f]{64}", str(vulnerability_database.get("sha256", "")))
        and input_verification.get("status") == "VERIFIED"
        and input_verification.get("databaseSha256") == vulnerability_database.get("sha256")
        and input_verification.get("scanResultSha256")
        == vulnerability_scan.get("resultSha256")
        and input_verification.get("scannedSbomSha256")
        == vulnerability_scan.get("sbomSha256")
        and re.fullmatch(r"[0-9a-f]{64}", str(vulnerability_scan.get("sbomSha256", "")))
        and vulnerability_scan.get("artifactSha256") == artifact_digest
        and re.fullmatch(r"[0-9a-f]{64}", str(vulnerability_scan.get("resultSha256", "")))
        and isinstance(component_count, int)
        and component_count > 0
        and component_count == sbom.get("runtimeDependencyCount")
        and isinstance(finding_count, int)
        and finding_count >= 0
        and isinstance(findings, list)
        and finding_count == len(findings)
        and vulnerability_review.get("status") == "COMPLETE"
        and bool(str(vulnerability_review.get("reviewer", "")).strip())
        and _is_timezone_instant(vulnerability_review.get("reviewedAt"))
        and vulnerability_review.get("findingsReviewed") == finding_count
        and all(_finding_has_review_disposition(finding) for finding in findings)
    )
    if not vulnerability_report_valid:
        blockers.append("reviewed local vulnerability inventory is required")
    return {"eligible": not blockers, "blockers": blockers}


def _is_timezone_instant(value: Any) -> bool:
    if not isinstance(value, str) or not value.strip():
        return False
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return False
    return parsed.tzinfo is not None and parsed.utcoffset() is not None


def _finding_has_review_disposition(
    finding: Any, now: datetime | None = None
) -> bool:
    if not isinstance(finding, dict):
        return False
    if not all(
        bool(str(finding.get(field, "")).strip())
        for field in ("id", "component", "version", "severity", "decision", "owner", "rationale")
    ) or finding.get("reviewed") is not True:
        return False
    if (
        "reachable" not in finding
        or finding.get("reachable") not in {True, False, None}
        or not isinstance(finding.get("knownExploited"), bool)
        or not isinstance(finding.get("fixAvailable"), bool)
        or str(finding.get("exposure", "")).casefold()
        not in {"public", "intranet", "local", "unknown"}
    ):
        return False

    decision = str(finding.get("decision", "")).upper()
    if decision == "NOT_APPLICABLE":
        return finding.get("reachable") is False
    if decision == "ACCEPTED_WITH_EXCEPTION":
        exception = finding.get("exception")
        if not isinstance(exception, dict):
            return False
        try:
            _validate_exception(
                str(finding.get("id")), exception, now or datetime.now(timezone.utc)
            )
        except ReleaseEvidenceError:
            return False
        return True
    return False


def assess_finding(
    finding: dict[str, Any],
    *,
    exception: dict[str, Any] | None = None,
    now: datetime | None = None,
) -> dict[str, Any]:
    """Classify synthetic/local scanner findings for contextual human triage."""
    finding_id = str(finding.get("id", "")).strip()
    severity = str(finding.get("severity", "unknown")).casefold()
    if not finding_id:
        raise ReleaseEvidenceError("finding id is required")
    if severity not in {"critical", "high", "medium", "low", "unknown"}:
        raise ReleaseEvidenceError("severity must be critical, high, medium, low, or unknown")
    reachable = finding.get("reachable")
    if reachable not in {True, False, None}:
        raise ReleaseEvidenceError("reachable must be true, false, or unknown")
    known_exploited = finding.get("knownExploited")
    fix_available = finding.get("fixAvailable")
    if not isinstance(known_exploited, bool) or not isinstance(fix_available, bool):
        raise ReleaseEvidenceError("knownExploited and fixAvailable must be explicit booleans")
    exposure = str(finding.get("exposure", "unknown")).casefold()
    if exposure not in {"public", "intranet", "local", "unknown"}:
        raise ReleaseEvidenceError("exposure must be public, intranet, local, or unknown")

    if known_exploited or (severity == "critical" and reachable is True):
        disposition = "IMMEDIATE_REMEDIATION_REVIEW"
    elif severity in {"critical", "high"} and (reachable is None or exposure == "unknown"):
        disposition = "CONTEXTUAL_REVIEW_REQUIRED"
    elif severity == "critical":
        disposition = "PRIORITIZED_REMEDIATION"
    elif severity == "high":
        disposition = "PRIORITIZED_REMEDIATION" if reachable else "CONTEXTUAL_REVIEW_REQUIRED"
    elif severity == "medium":
        disposition = "SCHEDULED_REMEDIATION"
    elif severity == "low":
        disposition = "TRACK"
    else:
        disposition = "MANUAL_TRIAGE_REQUIRED"

    assessed = {
        "findingId": finding_id,
        "severity": severity.upper(),
        "reachable": reachable,
        "knownExploited": known_exploited,
        "fixAvailable": fix_available,
        "exposure": exposure.upper(),
        "disposition": disposition,
        "releaseReviewRequired": severity in {"critical", "high", "unknown"} or known_exploited,
    }
    if exception is not None:
        _validate_exception(finding_id, exception, now or datetime.now(timezone.utc))
        assessed["exception"] = {
            "status": "ACTIVE_TIME_BOUNDED_REVIEW",
            "owner": exception["owner"],
            "expiresAt": exception["expiresAt"],
            "approvedBy": exception["approvedBy"],
        }
    return assessed


def _validate_exception(finding_id: str, exception: dict[str, Any], now: datetime) -> None:
    required = ("findingId", "owner", "rationale", "compensatingControls", "approvedBy", "expiresAt")
    if any(not exception.get(field) for field in required):
        raise ReleaseEvidenceError("vulnerability exception requires finding, owner, rationale, controls, approver, and expiry")
    if exception["findingId"] != finding_id:
        raise ReleaseEvidenceError("vulnerability exception finding id does not match")
    if not isinstance(exception["compensatingControls"], list) or not all(
        isinstance(control, str) and control.strip() for control in exception["compensatingControls"]
    ):
        raise ReleaseEvidenceError("vulnerability exception requires non-empty compensating controls")
    try:
        expiry = datetime.fromisoformat(str(exception["expiresAt"]).replace("Z", "+00:00"))
    except ValueError as error:
        raise ReleaseEvidenceError("vulnerability exception expiry must be ISO-8601 with timezone") from error
    if expiry.tzinfo is None:
        raise ReleaseEvidenceError("vulnerability exception expiry must include a timezone")
    if now.tzinfo is None:
        raise ReleaseEvidenceError("assessment time must include a timezone")
    if expiry <= now:
        raise ReleaseEvidenceError("vulnerability exception is expired")


def _validate_archive_name(name: str) -> None:
    if not name or "\\" in name or ":" in name:
        raise ReleaseEvidenceError("archive entry names must be non-empty POSIX relative paths")
    parts = name.split("/")
    path = PurePosixPath(name)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in parts):
        raise ReleaseEvidenceError("archive entry name is unsafe")
    if name.upper().startswith("META-INF/"):
        raise ReleaseEvidenceError("META-INF is reserved for JAR signature metadata")


def _fingerprint_from_rfc_cert(keytool: str, bundle: Path) -> str | None:
    try:
        result = subprocess.run(
            [keytool, "-printcert", "-jarfile", str(bundle), "-rfc"],
            check=False,
            capture_output=True,
            text=True,
            timeout=60,
        )
        match = PEM_CERT_PATTERN.search(result.stdout)
        if result.returncode != 0 or match is None:
            return None
        der = ssl.PEM_cert_to_DER_cert(match.group(0))
        return hashlib.sha256(der).hexdigest()
    except (OSError, subprocess.SubprocessError, ValueError):
        return None


def signer_fingerprint(keytool: str, bundle: Path) -> str | None:
    """Return the first signer certificate SHA-256 pin from a local JAR."""
    return _fingerprint_from_rfc_cert(keytool, bundle)


def write_signed_bundle(
    files: dict[str, Path],
    output: Path,
    keystore: Path,
    alias: str,
    password_environment_variable: str,
    environment: dict[str, str],
    jarsigner: str,
) -> None:
    """Create a signed local evidence archive; never overwrites an existing pack."""
    if output.exists():
        raise ReleaseEvidenceError("refusing to overwrite an existing evidence bundle")
    if not files:
        raise ReleaseEvidenceError("at least one evidence file is required")
    if not keystore.is_file() or not alias.strip():
        raise ReleaseEvidenceError("a local signing keystore and alias are required")
    if not password_environment_variable or not environment.get(password_environment_variable):
        raise ReleaseEvidenceError("keystore password must be supplied through an environment variable")
    seen: set[str] = set()
    for name, source in files.items():
        _validate_archive_name(name)
        if name in seen:
            raise ReleaseEvidenceError("duplicate archive entry name")
        if not source.is_file():
            raise ReleaseEvidenceError(f"evidence input is not a file: {name}")
        seen.add(name)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="ayuvahak-release-pack-", dir=output.parent) as temp:
        unsigned = Path(temp) / "unsigned.jar"
        with zipfile.ZipFile(unsigned, "x", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, source in sorted(files.items()):
                archive.write(source, arcname=name)
        command = [
            jarsigner,
            "-keystore",
            str(keystore),
            "-storetype",
            "PKCS12",
            "-storepass:env",
            password_environment_variable,
            "-keypass:env",
            password_environment_variable,
            "-digestalg",
            "SHA-256",
            "-sigalg",
            "SHA256withRSA",
            "-signedjar",
            str(output),
            str(unsigned),
            alias,
        ]
        try:
            result = subprocess.run(
                command,
                check=False,
                capture_output=True,
                text=True,
                timeout=180,
                env=environment,
            )
        except (OSError, subprocess.SubprocessError) as error:
            raise ReleaseEvidenceError("local JAR signing command could not be completed") from error
        if result.returncode != 0 or not output.is_file():
            output.unlink(missing_ok=True)
            raise ReleaseEvidenceError("local evidence signing failed; command output is suppressed")


def verify_signed_bundle(
    bundle: Path,
    expected_signer_sha256: str | None,
    keytool: str,
    jarsigner: str,
    expected_entries: set[str] | None = None,
) -> bool:
    """Verify JAR signatures, pinned signer identity, and optional entry set."""
    if expected_signer_sha256 is None or not re.fullmatch(
        r"(?:[0-9a-fA-F]{64}|(?:[0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2})",
        expected_signer_sha256.strip(),
    ):
        return False
    if not bundle.is_file():
        return False
    expected_fingerprint = expected_signer_sha256.replace(":", "").lower()
    actual_fingerprint = _fingerprint_from_rfc_cert(keytool, bundle)
    if actual_fingerprint != expected_fingerprint:
        return False
    try:
        with zipfile.ZipFile(bundle) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)):
                return False
            for name in names:
                if name.upper().startswith("META-INF/") and not _is_signature_metadata(name):
                    return False
            if expected_entries is not None:
                content_entries = {
                    name for name in names
                    if not name.upper().startswith("META-INF/")
                }
                if content_entries != expected_entries:
                    return False
        result = subprocess.run(
            [jarsigner, "-verify", "-certs", str(bundle)],
            check=False,
            capture_output=True,
            text=True,
            timeout=120,
        )
        verification_output = f"{result.stdout}\n{result.stderr}".casefold()
        return result.returncode == 0 and "jar verified." in verification_output
    except (OSError, subprocess.SubprocessError, zipfile.BadZipFile):
        return False


def _parse_assignment(value: str) -> tuple[str, str]:
    name, separator, path = value.partition("=")
    if not separator or not name or not path:
        raise argparse.ArgumentTypeError("expected NAME=PATH")
    return name, path


def _is_signature_metadata(name: str) -> bool:
    upper = name.upper()
    if upper == "META-INF/MANIFEST.MF":
        return True
    return re.fullmatch(r"META-INF/[A-Z0-9_-]+\.(?:SF|RSA|DSA|EC)", upper) is not None


def _command() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Local-only AyuVahak release evidence helpers")
    commands = parser.add_subparsers(dest="command", required=True)

    provenance = commands.add_parser("provenance", help="write SLSA-informed, non-level-claim provenance")
    provenance.add_argument("--source-revision", required=True)
    provenance.add_argument("--builder-id", required=True)
    provenance.add_argument("--build-type", required=True)
    provenance.add_argument("--version", required=True)
    provenance.add_argument("--parameter", action="append", default=[])
    provenance.add_argument("--artifact", action="append", type=_parse_assignment, required=True)
    provenance.add_argument("--output", required=True, type=Path)

    sbom = commands.add_parser("sbom", help="generate and validate an SPDX 3.0.1 SBOM offline")
    sbom.add_argument("--generator", required=True, type=Path)
    sbom.add_argument("--project-root", type=Path, default=Path.cwd())
    sbom.add_argument("--artifact", required=True, type=Path)
    sbom.add_argument("--application-purl", required=True)
    sbom.add_argument("--maven-repository", type=Path, default=Path.home() / ".m2" / "repository")
    sbom.add_argument("--validator-python", type=Path, default=Path(sys.executable))
    sbom.add_argument("--java-home", type=Path)
    sbom.add_argument("--output", required=True, type=Path)
    sbom.add_argument("--validation-report", required=True, type=Path)

    manifest = commands.add_parser("manifest", help="write a manifest bound to locally validated SPDX 3.0.1")
    manifest.add_argument("--version", required=True)
    manifest.add_argument("--source-revision", required=True)
    manifest.add_argument("--builder-id", required=True)
    manifest.add_argument("--build-type", required=True)
    manifest.add_argument("--parameter", action="append", default=[])
    manifest.add_argument("--artifact", action="append", type=_parse_assignment, required=True)
    manifest.add_argument("--migration-range", required=True)
    manifest.add_argument("--supported-upgrade-path", required=True)
    manifest.add_argument("--sbom", type=Path)
    manifest.add_argument("--sbom-report", type=Path)
    manifest.add_argument("--vulnerability-report", type=Path)
    manifest.add_argument("--vulnerability-database", type=Path)
    manifest.add_argument("--vulnerability-scan-result", type=Path)
    manifest.add_argument("--vulnerability-scan-sbom", type=Path)
    manifest.add_argument("--validator-python", type=Path, default=Path(sys.executable))
    manifest.add_argument("--output", required=True, type=Path)

    assess = commands.add_parser("assess", help="classify a local vulnerability inventory")
    assess.add_argument("--input", required=True, type=Path)
    assess.add_argument("--output", required=True, type=Path)
    assess.add_argument("--as-of", required=True, help="ISO-8601 instant with timezone")

    sign = commands.add_parser("sign-bundle", help="sign a local test/evidence archive")
    sign.add_argument("--output", required=True, type=Path)
    sign.add_argument("--keystore", required=True, type=Path)
    sign.add_argument("--alias", required=True)
    sign.add_argument("--password-env", required=True)
    sign.add_argument("--file", action="append", type=_parse_assignment, required=True)

    verify = commands.add_parser("verify-bundle", help="verify a bundle and pinned signer")
    verify.add_argument("--bundle", required=True, type=Path)
    verify.add_argument("--signer-sha256", required=True)
    verify.add_argument("--entry", action="append", default=[])

    return parser


def main(argv: list[str] | None = None) -> int:
    arguments = _command().parse_args(argv)
    try:
        if arguments.command == "sbom":
            report = generate_and_validate_spdx(
                generator=arguments.generator,
                project_root=arguments.project_root,
                artifact=arguments.artifact,
                application_purl=arguments.application_purl,
                maven_repository=arguments.maven_repository,
                validator_python=arguments.validator_python,
                output=arguments.output,
                validation_report=arguments.validation_report,
                java_home=arguments.java_home,
            )
            print(json.dumps({
                "result": report["result"],
                "format": report["format"],
                "sbomSha256": report["sbomSha256"],
                "applicationArtifactSha256": report["applicationArtifactSha256"],
                "runtimePackageCount": report["runtimePackageCount"],
                "runtimeDependencyCount": report["runtimeDependencyCount"],
                "declaredLicenseRelationshipCount": report["declaredLicenseRelationshipCount"],
                "generatorVersion": report["generator"]["version"],
            }, sort_keys=True))
            return 0

        if arguments.command == "provenance":
            validate_version(arguments.version)
            parameters: dict[str, str] = {}
            for pair in arguments.parameter:
                key, separator, value = pair.partition("=")
                if not separator or not key:
                    raise ReleaseEvidenceError("build parameters must use KEY=VALUE")
                parameters[key] = value
            artifacts = {name: sha256_file(Path(path)) for name, path in arguments.artifact}
            result = build_provenance(
                source_revision=arguments.source_revision,
                builder_id=arguments.builder_id,
                build_type=arguments.build_type,
                parameters={**parameters, "releaseVersion": arguments.version},
                artifacts=artifacts,
            )
            arguments.output.parent.mkdir(parents=True, exist_ok=True)
            with arguments.output.open("x", encoding="utf-8", newline="\n") as destination:
                json.dump(result, destination, indent=2, sort_keys=True)
                destination.write("\n")
            print("Provenance written; SLSA level/conformance is not claimed.")
            return 0

        if arguments.command == "manifest":
            if bool(arguments.sbom) != bool(arguments.sbom_report):
                raise ReleaseEvidenceError("--sbom and --sbom-report must be supplied together")
            if arguments.vulnerability_report is not None and arguments.sbom is None:
                raise ReleaseEvidenceError("--vulnerability-report requires a validated --sbom")
            vulnerability_inputs = (
                arguments.vulnerability_database,
                arguments.vulnerability_scan_result,
                arguments.vulnerability_scan_sbom,
            )
            if any(value is not None for value in vulnerability_inputs) and any(
                value is None for value in vulnerability_inputs
            ):
                raise ReleaseEvidenceError(
                    "--vulnerability-database, --vulnerability-scan-result, and --vulnerability-scan-sbom must be supplied together"
                )
            if arguments.vulnerability_report is None and any(
                value is not None for value in vulnerability_inputs
            ):
                raise ReleaseEvidenceError(
                    "vulnerability input paths require --vulnerability-report"
                )
            if arguments.vulnerability_report is not None and (
                any(value is None for value in vulnerability_inputs)
            ):
                raise ReleaseEvidenceError(
                    "--vulnerability-report requires the local database, scan-result, and scanned-SBOM bytes"
                )
            parameters: dict[str, str] = {}
            for pair in arguments.parameter:
                key, separator, value = pair.partition("=")
                if not separator or not key:
                    raise ReleaseEvidenceError("build parameters must use KEY=VALUE")
                parameters[key] = value
            artifacts = {name: sha256_file(Path(path)) for name, path in arguments.artifact}
            if arguments.sbom is None:
                manifest = build_incomplete_manifest(
                    version=arguments.version,
                    source_revision=arguments.source_revision,
                    builder_id=arguments.builder_id,
                    build_type=arguments.build_type,
                    build_parameters=parameters,
                    artifacts=artifacts,
                    migration_range=arguments.migration_range,
                    supported_upgrade_path=arguments.supported_upgrade_path,
                )
            else:
                validation_script = Path(__file__).with_name("validate_spdx31_offline.py")
                validated = subprocess.run(
                    [str(arguments.validator_python.resolve()), str(validation_script), str(arguments.sbom.resolve())],
                    check=False,
                    capture_output=True,
                    text=True,
                    timeout=300,
                )
                try:
                    validation_report = json.loads(validated.stdout.strip())
                    generation_report = json.loads(arguments.sbom_report.read_text(encoding="utf-8"))
                except json.JSONDecodeError as error:
                    raise ReleaseEvidenceError("SBOM validation/generation report is not valid JSON") from error
                if validated.returncode != 0 or validation_report.get("result") != "PASS":
                    raise ReleaseEvidenceError("SBOM failed local SPDX 3.0.1 validation")
                actual_sbom_digest = sha256_file(arguments.sbom)
                if (
                    generation_report.get("result") != "PASS"
                    or generation_report.get("format") != validation_report.get("format")
                    or generation_report.get("sbomSha256") != actual_sbom_digest
                    or validation_report.get("sbomSha256") != actual_sbom_digest
                ):
                    raise ReleaseEvidenceError("SBOM generation report does not bind this validated SBOM")
                artifact_digest = generation_report.get("applicationArtifactSha256")
                if artifact_digest not in artifacts.values():
                    raise ReleaseEvidenceError("provenance artifact set does not contain the SBOM-bound application JAR")
                vulnerability_evidence = None
                if arguments.vulnerability_report is not None:
                    vulnerability_evidence = load_vulnerability_report(
                        arguments.vulnerability_report,
                        database_path=arguments.vulnerability_database,
                        scan_result_path=arguments.vulnerability_scan_result,
                        scanned_sbom_path=arguments.vulnerability_scan_sbom,
                    )
                sbom_evidence = {
                    "requiredFormat": "SPDX-3.0",
                    "format": validation_report["format"],
                    "status": "VALIDATED",
                    "sha256": actual_sbom_digest,
                    "validator": "offline JSON Schema + SHACL validator",
                    "validationProfile": "official SPDX 3.0.1 JSON Schema + model",
                    "schemaSha256": validation_report["schema"]["sha256"],
                    "modelSha256": validation_report["shacl"]["sha256"],
                    "contextSha256": validation_report["contextSha256"],
                    "licenseListSha256": validation_report["licenseList"]["sha256"],
                    "validationReportSha256": sha256_file(arguments.sbom_report),
                    "validationReport": validation_report,
                    "generator": generation_report["generator"],
                    "applicationArtifactSha256": artifact_digest,
                    "runtimePackageCount": generation_report.get("runtimePackageCount"),
                    "runtimeDependencyCount": generation_report.get("runtimeDependencyCount"),
                }
                manifest = build_manifest(
                    version=arguments.version,
                    source_revision=arguments.source_revision,
                    builder_id=arguments.builder_id,
                    build_type=arguments.build_type,
                    build_parameters=parameters,
                    artifacts=artifacts,
                    migration_range=arguments.migration_range,
                    supported_upgrade_path=arguments.supported_upgrade_path,
                    sbom=sbom_evidence,
                    vulnerability_report=vulnerability_evidence,
                )
            arguments.output.parent.mkdir(parents=True, exist_ok=True)
            with arguments.output.open("x", encoding="utf-8", newline="\n") as destination:
                json.dump(manifest, destination, indent=2, sort_keys=True)
                destination.write("\n")
            print("Manifest written; eligibility reflects only locally bound evidence present in the manifest.")
            return 0

        if arguments.command == "assess":
            source = json.loads(arguments.input.read_text(encoding="utf-8"))
            now = datetime.fromisoformat(arguments.as_of.replace("Z", "+00:00"))
            if now.tzinfo is None:
                raise ReleaseEvidenceError("assessment time must include a timezone")
            exceptions = {
                entry.get("findingId"): entry for entry in source.get("exceptions", [])
            }
            if len(exceptions) != len(source.get("exceptions", [])):
                raise ReleaseEvidenceError("duplicate vulnerability exception finding id")
            findings = [
                assess_finding(item, exception=exceptions.get(item.get("id")), now=now)
                for item in source.get("findings", [])
            ]
            arguments.output.parent.mkdir(parents=True, exist_ok=True)
            with arguments.output.open("x", encoding="utf-8", newline="\n") as destination:
                json.dump({"findings": findings}, destination, indent=2, sort_keys=True)
                destination.write("\n")
            print(f"Classified {len(findings)} local finding(s); human review remains required per policy.")
            return 0

        if arguments.command == "sign-bundle":
            files = {name: Path(path) for name, path in arguments.file}
            keytool = shutil.which("keytool")
            jarsigner = shutil.which("jarsigner")
            if not keytool or not jarsigner:
                raise ReleaseEvidenceError("a local JDK keytool and jarsigner are required")
            write_signed_bundle(
                files,
                arguments.output,
                arguments.keystore,
                arguments.alias,
                arguments.password_env,
                dict(os.environ),
                jarsigner,
            )
            fingerprint = signer_fingerprint(keytool, arguments.output)
            if fingerprint is None:
                raise ReleaseEvidenceError("signed bundle does not expose a verifiable signer certificate")
            print(f"Signed local evidence bundle; signer SHA-256: {fingerprint}")
            return 0

        if arguments.command == "verify-bundle":
            keytool = shutil.which("keytool")
            jarsigner = shutil.which("jarsigner")
            if not keytool or not jarsigner:
                raise ReleaseEvidenceError("a local JDK keytool and jarsigner are required")
            valid = verify_signed_bundle(
                arguments.bundle,
                arguments.signer_sha256,
                keytool,
                jarsigner,
                set(arguments.entry) if arguments.entry else None,
            )
            print("verified" if valid else "verification failed")
            return 0 if valid else 2

        raise ReleaseEvidenceError("unsupported command")
    except (OSError, json.JSONDecodeError, ReleaseEvidenceError, SbomGenerationError, ValueError) as error:
        print(f"release-evidence: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
