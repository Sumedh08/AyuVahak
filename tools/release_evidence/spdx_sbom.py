"""Generate and validate an offline SPDX 3.0.1 SBOM for the local distribution."""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from spdx_normalize import normalize_spdx_document


CDXGEN_VERSION = "13.4.0"
CDXGEN_WINDOWS_AMD64_SHA256 = "0b4a6ec411a70127d72e2f6fc45277f03fa64d4237a40325d0ff117634b76375"
LOOPBACK_HOSTS = "localhost,127.0.0.1,::1"
SECRET_ENV_PATTERN = re.compile(r"TOKEN|PASSWORD|SECRET|CREDENTIAL|PRIVATE.?KEY|API.?KEY", re.IGNORECASE)
UNSAFE_ENV_NAMES = {
    "MAVEN_OPTS",
    "MAVEN_ARGS",
    "MAVEN_CENTRAL_URL",
    "FETCH_LICENSE",
    "CDXGEN_METADATA_PROPERTIES",
    "JAVA_TOOL_OPTIONS",
    "JDK_JAVA_OPTIONS",
    "HTTP_PROXY",
    "HTTPS_PROXY",
    "ALL_PROXY",
    "PYTHONPATH",
    "PYTHONHOME",
    "PYTHONUSERBASE",
    "NODE_OPTIONS",
    "NODE_PATH",
    "MAVEN_HOME",
    "M2_HOME",
}


class SbomGenerationError(ValueError):
    """Raised when local SPDX generation or validation is incomplete or unsafe."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _clean_environment() -> dict[str, str]:
    environment = dict(os.environ)
    for key in list(environment):
        upper = key.upper()
        if (
            upper in UNSAFE_ENV_NAMES
            or SECRET_ENV_PATTERN.search(upper)
            or upper.startswith(("AWS_", "AZURE_", "GOOGLE_", "GCP_"))
        ):
            environment.pop(key, None)
    return environment


def _local_only_environment(
    *,
    project_root: Path,
    maven_repository: Path,
    temp_root: Path,
) -> tuple[dict[str, str], str]:
    wrapper = "mvnw.cmd" if os.name == "nt" else "./mvnw"
    environment = _clean_environment()
    environment.update(
        {
            "MVN_CMD": wrapper,
            # The repository-owned .mvn/maven.config selects .mvn/settings.xml.
            # Keep it so cached artifacts retain the configured mirror identity;
            # the wrapper must still run offline and cdxgen is loopback-only.
            "MVN_ARGS": "-o -Dscope=runtime",
            "MAVEN_CACHE_DIR": str(maven_repository),
            "PREFER_MAVEN_DEPS_TREE": "true",
            "CDXGEN_ALLOWED_HOSTS": LOOPBACK_HOSTS,
            "CDXGEN_ALLOWED_COMMANDS": wrapper,
            "NO_PROXY": LOOPBACK_HOSTS,
            "no_proxy": LOOPBACK_HOSTS,
            "CDXGEN_TRACE_MODE": "true",
            "CDXGEN_TRACE_LOG": str(temp_root / "cdxgen-trace.jsonl"),
            "CDXGEN_TEMP_DIR": str(temp_root),
            "CDXGEN_LOG_FORMAT": "json",
            "CDXGEN_NO_CACHE": "true",
            "CDXGEN_LICENSE_ENHANCE": "false",
            "CDXGEN_LICENSE_ENRICH": "false",
            "CDXGEN_NO_IGNORE": "false",
        }
    )
    environment.pop("FETCH_LICENSE", None)
    if os.name == "nt" and not (project_root / wrapper).is_file():
        raise SbomGenerationError("the local Maven wrapper is missing")
    if os.name != "nt" and not (project_root / "mvnw").is_file():
        raise SbomGenerationError("the local Maven wrapper is missing")
    return environment, wrapper


def _validate_repository_maven_settings(project_root: Path) -> None:
    """Fail closed unless project Maven settings are local and credential-free."""
    config_path = project_root / ".mvn" / "maven.config"
    settings_path = project_root / ".mvn" / "settings.xml"
    if not config_path.is_file() or not settings_path.is_file():
        raise SbomGenerationError("repository-owned offline Maven settings are required")
    config = config_path.read_text(encoding="utf-8")
    if "--settings=.mvn/settings.xml" not in config and "--settings .mvn/settings.xml" not in config:
        raise SbomGenerationError("Maven config does not select the repository-owned settings file")
    try:
        root = ET.parse(settings_path).getroot()
    except ET.ParseError as error:
        raise SbomGenerationError("repository-owned Maven settings are invalid XML") from error
    def direct_child(parent: ET.Element, name: str) -> ET.Element | None:
        return next(
            (child for child in parent if child.tag.rsplit("}", 1)[-1].casefold() == name),
            None,
        )

    servers = direct_child(root, "servers")
    proxies = direct_child(root, "proxies")
    has_server = servers is not None and any(
        child.tag.rsplit("}", 1)[-1].casefold() == "server" for child in servers
    )
    has_proxy = proxies is not None and any(
        child.tag.rsplit("}", 1)[-1].casefold() == "proxy" for child in proxies
    )
    has_credentials = any(
        element.tag.rsplit("}", 1)[-1].casefold()
        in {"username", "password", "passphrase", "privatekey"}
        for element in root.iter()
    )
    if has_server or has_proxy or has_credentials:
        raise SbomGenerationError(
            "repository-owned Maven settings must not contain servers, proxies, or credential fields"
        )


def _validate_cached_maven_distribution(project_root: Path) -> None:
    """Prevent the Maven wrapper from downloading its distribution during scanning."""
    properties_path = project_root / ".mvn" / "wrapper" / "maven-wrapper.properties"
    if not properties_path.is_file():
        raise SbomGenerationError("Maven wrapper distribution properties are required")
    properties: dict[str, str] = {}
    for line in properties_path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith(("#", "!")) or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        properties[key.strip()] = value.strip()
    distribution_url = properties.get("distributionUrl", "")
    distribution_name = Path(urlparse(distribution_url).path).name
    if not distribution_name.endswith("-bin.zip"):
        raise SbomGenerationError("Maven wrapper distribution URL is not a pinned binary archive")
    distribution_dir = distribution_name.removesuffix("-bin.zip")
    cached_root = Path.home() / ".m2" / "wrapper" / "dists" / distribution_dir
    executable = "mvn.cmd" if os.name == "nt" else "mvn"
    if not cached_root.is_dir() or not any(
        (candidate / "bin" / executable).is_file()
        for candidate in cached_root.iterdir()
        if candidate.is_dir()
    ):
        raise SbomGenerationError(
            "pinned Maven wrapper distribution is not cached; refusing a network-capable bootstrap"
        )


def _check_trace(trace_path: Path, wrapper: str) -> None:
    if not trace_path.is_file():
        raise SbomGenerationError("cdxgen did not produce the required local execution trace")
    records: list[dict[str, Any]] = []
    for line in trace_path.read_text(encoding="utf-8").splitlines():
        try:
            record = json.loads(line)
        except json.JSONDecodeError as error:
            raise SbomGenerationError("cdxgen execution trace is not valid JSONL") from error
        if isinstance(record, dict):
            records.append(record)
    if not records:
        raise SbomGenerationError("cdxgen execution trace is empty")
    spawned = [record for record in records if record.get("type") == "spawn"]
    if not spawned or any(Path(str(record.get("command", ""))).name.casefold() != wrapper.casefold()
                          for record in spawned):
        raise SbomGenerationError("cdxgen ran an unexpected external command")
    network_records = [
        record for record in records
        if str(record.get("type", "")).casefold() in {"host", "network", "request", "http", "fetch"}
    ]
    if network_records:
        raise SbomGenerationError("cdxgen attempted remote network access; refusing the SBOM")


def _build_cdxgen_command(
    generator: Path, project_root: Path, output: Path, version: str
) -> list[str]:
    """Inventory backend and locked browser dependency graphs in one document."""
    return [
        str(generator),
        "--type", "java",
        "--type", "js",
        "--format", "spdx",
        "--recurse",
        "--no-install-deps",
        "--fail-on-error",
        "--no-cache",
        "--project-name", "AyuVahak",
        "--project-version", version,
        "--output", str(output),
        str(project_root),
    ]


def generate_and_validate_spdx(
    *,
    generator: Path,
    project_root: Path,
    artifact: Path,
    application_purl: str,
    maven_repository: Path,
    validator_python: Path,
    output: Path,
    validation_report: Path,
    java_home: Path | None = None,
) -> dict[str, Any]:
    """Run pinned cdxgen offline, normalize with local evidence, and validate."""
    generator = generator.resolve()
    project_root = project_root.resolve()
    artifact = artifact.resolve()
    maven_repository = maven_repository.resolve()
    validator_python = validator_python.resolve()
    output = output.resolve()
    validation_report = validation_report.resolve()
    if not generator.is_file() or not artifact.is_file() or not validator_python.is_file():
        raise SbomGenerationError("generator, artifact, and validator Python executable must exist locally")
    try:
        artifact.relative_to(project_root)
    except ValueError as error:
        raise SbomGenerationError("application artifact must be inside the local project root") from error
    if output.exists() or validation_report.exists():
        raise SbomGenerationError("SBOM and validation report paths must not already exist")
    if not maven_repository.is_dir():
        raise SbomGenerationError("the offline Maven cache directory must exist")
    version_environment = _clean_environment()
    version_environment.update(
        {
            "CDXGEN_ALLOWED_HOSTS": LOOPBACK_HOSTS,
            "CDXGEN_ALLOWED_COMMANDS": "",
            "CDXGEN_NO_CACHE": "true",
        }
    )

    version_result = subprocess.run(
        [str(generator), "--version"],
        cwd=project_root,
        env=version_environment,
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    )
    version_output = f"{version_result.stdout}\n{version_result.stderr}"
    if version_result.returncode != 0 or not re.search(rf"\b{re.escape(CDXGEN_VERSION)}\b", version_output):
        raise SbomGenerationError(f"expected cdxgen {CDXGEN_VERSION}; generator identity did not match")
    generator_digest = sha256_file(generator)
    if generator_digest != CDXGEN_WINDOWS_AMD64_SHA256:
        raise SbomGenerationError("generator digest is not the pinned official cdxgen v13.4.0 Windows AMD64 asset")

    output.parent.mkdir(parents=True, exist_ok=True)
    validation_report.parent.mkdir(parents=True, exist_ok=True)
    _validate_repository_maven_settings(project_root)
    _validate_cached_maven_distribution(project_root)
    with tempfile.TemporaryDirectory(prefix="ayuvahak-spdx-", dir=output.parent) as scratch:
        temp_root = Path(scratch)
        raw_output = temp_root / "generated.spdx.json"
        environment, wrapper = _local_only_environment(
            project_root=project_root,
            maven_repository=maven_repository,
            temp_root=temp_root,
        )
        if java_home is not None:
            if not java_home.is_dir():
                raise SbomGenerationError("configured local JAVA_HOME does not exist")
            environment["JAVA_HOME"] = str(java_home.resolve())

        version = application_purl.split("@", 1)[1].split("?", 1)[0]
        command = _build_cdxgen_command(generator, project_root, raw_output, version)
        generated = subprocess.run(
            command,
            cwd=project_root,
            env=environment,
            check=False,
            capture_output=True,
            text=True,
            timeout=1800,
        )
        trace_path = temp_root / "cdxgen-trace.jsonl"
        _check_trace(trace_path, wrapper)
        if generated.returncode != 0 or not raw_output.is_file():
            detail = (generated.stderr or generated.stdout).strip()[-2000:]
            raise SbomGenerationError(
                "cdxgen failed or did not produce SPDX output; incomplete output rejected"
                + (f" (exit {generated.returncode}): {detail}" if detail else "")
            )

        raw_document = json.loads(raw_output.read_text(encoding="utf-8"))
        normalized = normalize_spdx_document(
            raw_document,
            artifact_sha256=sha256_file(artifact),
            application_purl=application_purl,
            maven_repository=maven_repository,
        )
        candidate_path = temp_root / "normalized.spdx.json"
        candidate_path.write_bytes(
            (json.dumps(normalized, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
        )
        validator_script = Path(__file__).with_name("validate_spdx31_offline.py")
        validated = subprocess.run(
            [str(validator_python), str(validator_script), str(candidate_path)],
            cwd=project_root,
            env=_clean_environment(),
            check=False,
            capture_output=True,
            text=True,
            timeout=300,
        )
        try:
            validation = json.loads(validated.stdout.strip())
        except json.JSONDecodeError as error:
            raise SbomGenerationError("offline validator did not return a machine-readable report") from error
        if validated.returncode != 0 or validation.get("result") != "PASS":
            raise SbomGenerationError("SPDX 3.0.1 schema/SHACL validation failed")

        output.write_bytes(candidate_path.read_bytes())
        report = {
            "result": "PASS",
            "format": validation["format"],
            "generator": {
                "name": "cdxgen",
                "version": CDXGEN_VERSION,
                "sha256": generator_digest,
            },
            "validator": {
                "name": "offline JSON Schema + SHACL validator",
                "schemaSha256": validation["schema"]["sha256"],
                "modelSha256": validation["shacl"]["sha256"],
                "contextSha256": validation["contextSha256"],
                "licenseListSha256": validation["licenseList"]["sha256"],
            },
            "sbomSha256": validation["sbomSha256"],
            "applicationArtifactSha256": sha256_file(artifact),
            "applicationPurl": application_purl,
            "runtimePackageCount": sum(
                item.get("type") == "software_Package" for item in normalized["@graph"]
            ),
            "runtimeDependencyCount": sum(
                item.get("type") == "software_Package"
                and item.get("software_packageUrl") != application_purl
                for item in normalized["@graph"]
            ),
            "declaredLicenseRelationshipCount": sum(
                item.get("relationshipType") == "hasDeclaredLicense" for item in normalized["@graph"]
            ),
            "validation": validation,
            "networkPolicy": "loopback-only; Maven offline; no external hosts observed",
        }
        if sha256_file(output) != report["sbomSha256"]:
            output.unlink(missing_ok=True)
            raise SbomGenerationError("written SPDX SBOM digest changed after validation")
        validation_report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        return report
