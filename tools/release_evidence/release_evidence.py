#!/usr/bin/env python3
"""Local-only helpers for preparing and checking AyuVahak release evidence.

This module intentionally does not generate or validate SPDX documents. The
Phase 13 release gate requires a validated SPDX 3.0 SBOM; until an approved,
locally available generator and validator are selected, manifests produced by
this module remain ineligible for release.
"""

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


MANIFEST_SCHEMA = "ayuvahak.release-evidence/v1"
PROVENANCE_SCHEMA = "ayuvahak.release-provenance/v1"
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
        "releaseEligibility": eligibility,
        "generatedAt": (generated_at or datetime.now(timezone.utc))
        .astimezone(timezone.utc)
        .isoformat()
        .replace("+00:00", "Z"),
    }


def release_eligibility(evidence: dict[str, Any]) -> dict[str, Any]:
    """Apply fail-closed prerequisites; this partial tool cannot grant eligibility."""
    blockers: list[str] = []
    sbom = evidence.get("sbom") or {}
    if (
        sbom.get("status") != "VALIDATED"
        or not str(sbom.get("format", "")).startswith("SPDX-3.0")
        or not sbom.get("validator")
        or not sbom.get("validationProfile")
        or not re.fullmatch(r"[0-9a-f]{64}", str(sbom.get("sha256", "")))
    ):
        blockers.append("validated SPDX 3.0 SBOM evidence is required")
    if (evidence.get("provenance") or {}).get("status") != "VERIFIED":
        blockers.append("verified source/build/artifact provenance is required")
    if (evidence.get("signature") or {}).get("status") != "VERIFIED":
        blockers.append("verified signature and expected signer identity are required")
    if (evidence.get("vulnerabilityReport") or {}).get("status") != "REVIEWED":
        blockers.append("reviewed local vulnerability inventory is required")
    blockers.append(
        "SPDX 3.0 generation and schema/profile validation are not implemented or configured locally"
    )
    return {"eligible": not blockers, "blockers": blockers}


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
            timeout=20,
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
                timeout=60,
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
            timeout=30,
        )
        return result.returncode == 0 and "jar verified." in result.stdout.casefold()
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

    manifest = commands.add_parser("manifest", help="write a manifest that remains blocked without SPDX 3.0")
    manifest.add_argument("--version", required=True)
    manifest.add_argument("--source-revision", required=True)
    manifest.add_argument("--builder-id", required=True)
    manifest.add_argument("--build-type", required=True)
    manifest.add_argument("--parameter", action="append", default=[])
    manifest.add_argument("--artifact", action="append", type=_parse_assignment, required=True)
    manifest.add_argument("--migration-range", required=True)
    manifest.add_argument("--supported-upgrade-path", required=True)
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
            parameters: dict[str, str] = {}
            for pair in arguments.parameter:
                key, separator, value = pair.partition("=")
                if not separator or not key:
                    raise ReleaseEvidenceError("build parameters must use KEY=VALUE")
                parameters[key] = value
            artifacts = {name: sha256_file(Path(path)) for name, path in arguments.artifact}
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
            arguments.output.parent.mkdir(parents=True, exist_ok=True)
            with arguments.output.open("x", encoding="utf-8", newline="\n") as destination:
                json.dump(manifest, destination, indent=2, sort_keys=True)
                destination.write("\n")
            print("Manifest written as blocked; it is not release-eligible without validated SPDX 3.0.")
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
    except (OSError, json.JSONDecodeError, ReleaseEvidenceError, ValueError) as error:
        print(f"release-evidence: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
