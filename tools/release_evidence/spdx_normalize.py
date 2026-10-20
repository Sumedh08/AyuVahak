"""Conservative SPDX 3.0 normalization for locally generated Maven SBOMs."""

from __future__ import annotations

import copy
import hashlib
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import unquote
from typing import Any


SPDX_VERSION = "3.0.1"
SPDX_LICENSE_LIST_VERSION = "3.29.0"
CDXGEN_CREATOR = "https://github.com/cdxgen/cdxgen"
MAVEN_POM_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}

# These declarations are deliberately exact. Unrecognized and ambiguous POM
# labels remain unasserted instead of being guessed or normalized by name
# similarity. Every result below is an identifier in the pinned SPDX list.
KNOWN_MAVEN_LICENSE_DECLARATIONS = {
    "apache 2": "Apache-2.0",
    "apache 2.0": "Apache-2.0",
    "apache license 2.0": "Apache-2.0",
    "apache license version 2.0": "Apache-2.0",
    "apache license, version 2.0": "Apache-2.0",
    "apache-2.0": "Apache-2.0",
    "the apache license, version 2.0": "Apache-2.0",
    "the apache software license, version 2.0": "Apache-2.0",
    "bsd 3 clause": "BSD-3-Clause",
    "bsd-3-clause": "BSD-3-Clause",
    "bsd-2-clause": "BSD-2-Clause",
    "the bsd 2-clause license": "BSD-2-Clause",
    "epl 2.0": "EPL-2.0",
    "mit": "MIT",
    "mit license": "MIT",
    "the mit license": "MIT",
    "mpl 1.1": "MPL-1.1",
    "mozilla public license version 2.0": "MPL-2.0",
    "public domain, per creative commons cc0": "CC0-1.0",
    "unicode-3.0": "Unicode-3.0",
}
SUPPORTED_SPDX_LICENSE_IDS = frozenset(KNOWN_MAVEN_LICENSE_DECLARATIONS.values())


def normalize_spdx_document(
    source: dict[str, Any],
    *,
    artifact_sha256: str,
    application_purl: str,
    maven_repository: Path,
) -> dict[str, Any]:
    """Bind the built application artifact and add only evidenced licenses.

    This function does not assert license conclusions, authorship, or an SPDX
    profile. It preserves the generator graph and makes the external creator
    reference explicit as an ExternalMap.
    """
    if not re.fullmatch(r"[0-9a-f]{64}", artifact_sha256):
        raise ValueError("application artifact digest must be lowercase SHA-256")
    if not application_purl.startswith("pkg:maven/"):
        raise ValueError("the application package must use its Maven PURL")

    document = copy.deepcopy(source)
    graph = document.get("@graph")
    if not isinstance(graph, list):
        raise ValueError("SPDX document must contain an @graph array")
    documents = [item for item in graph if item.get("type") == "SpdxDocument"]
    creations = [item for item in graph if item.get("type") == "CreationInfo"]
    if len(documents) != 1 or len(creations) != 1:
        raise ValueError("expected one SPDX document and one creation info element")
    spdx_document = documents[0]
    creation = creations[0]
    document_id = spdx_document.get("spdxId")
    creation_id = creation.get("@id") or creation.get("spdxId")
    if not isinstance(document_id, str) or not isinstance(creation_id, str):
        raise ValueError("SPDX document and creation info identifiers are required")
    if CDXGEN_CREATOR not in creation.get("createdBy", []):
        raise ValueError("unexpected SPDX creator; refusing to normalize")

    # cdxgen 13.4.0 emits an optional profile value that fails the pinned 3.0.1
    # semantic context. Omit that unsupported claim rather than altering it.
    spdx_document.pop("profileConformance", None)
    imports = spdx_document.setdefault("import", [])
    if not isinstance(imports, list):
        raise ValueError("SPDX document import must be an array")
    if not any(
        isinstance(item, dict)
        and item.get("type") == "ExternalMap"
        and item.get("externalSpdxId") == CDXGEN_CREATOR
        for item in imports
    ):
        imports.append({"type": "ExternalMap", "externalSpdxId": CDXGEN_CREATOR})

    packages = [item for item in graph if item.get("type") == "software_Package"]
    application_packages = [
        item for item in packages if item.get("software_packageUrl") == application_purl
    ]
    if len(application_packages) != 1:
        raise ValueError("application Maven PURL must identify exactly one package")
    _attach_artifact_hash(application_packages[0], artifact_sha256)

    license_nodes: dict[str, dict[str, Any]] = {}
    for item in graph:
        if item.get("type") == "simplelicensing_LicenseExpression":
            expression = item.get("simplelicensing_licenseExpression")
            if isinstance(expression, str):
                license_nodes.setdefault(expression, item)

    for package in packages:
        pom = _local_maven_pom(
            package.get("software_packageUrl", ""),
            package.get("software_packageVersion", ""),
            maven_repository,
        )
        if pom is None:
            continue
        try:
            root = ET.parse(pom).getroot()
        except ET.ParseError as error:
            raise ValueError(f"invalid local Maven POM for {package.get('name', 'package')}") from error
        for license_node in root.findall("m:licenses/m:license", MAVEN_POM_NAMESPACE):
            name = (license_node.findtext("m:name", default="", namespaces=MAVEN_POM_NAMESPACE) or "").strip()
            expression = KNOWN_MAVEN_LICENSE_DECLARATIONS.get(_normalize_license_name(name))
            if expression is None or expression not in SUPPORTED_SPDX_LICENSE_IDS:
                continue
            license_element = license_nodes.get(expression)
            if license_element is None:
                license_element = {
                    "type": "simplelicensing_LicenseExpression",
                    "spdxId": f"{document_id}#LicenseExpression-{expression}",
                    "creationInfo": creation_id,
                    "simplelicensing_licenseExpression": expression,
                    "simplelicensing_licenseListVersion": SPDX_LICENSE_LIST_VERSION,
                }
                graph.append(license_element)
                license_nodes[expression] = license_element
            _add_license_relationship(graph, package, license_element, creation_id, document_id)

    document["@graph"] = graph
    return document


def _attach_artifact_hash(package: dict[str, Any], digest: str) -> None:
    hashes = package.setdefault("verifiedUsing", [])
    if not isinstance(hashes, list):
        raise ValueError("package verifiedUsing must be an array")
    existing = [
        item for item in hashes
        if isinstance(item, dict) and item.get("type") == "Hash" and item.get("algorithm") == "sha256"
    ]
    if existing and any(item.get("hashValue") != digest for item in existing):
        raise ValueError("application package already has a conflicting SHA-256 hash")
    if not existing:
        hashes.append({"type": "Hash", "algorithm": "sha256", "hashValue": digest})


def _local_maven_pom(purl: str, version: str, repository: Path) -> Path | None:
    if not purl.startswith("pkg:maven/") or not version:
        return None
    coordinates = purl[len("pkg:maven/"):].split("?", 1)[0]
    namespace, separator, package_version = coordinates.partition("@")
    if not separator or package_version != version:
        return None
    parts = [unquote(part) for part in namespace.split("/")]
    if len(parts) < 2 or any(not part or part in {".", ".."} for part in parts):
        return None
    group, artifact = parts[0], parts[-1]
    group_path = group.replace(".", "/")
    local_root = repository.resolve()
    pom = (local_root / group_path / artifact / version / f"{artifact}-{version}.pom").resolve()
    try:
        pom.relative_to(local_root)
    except ValueError:
        return None
    return pom if pom.is_file() else None


def _normalize_license_name(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip().casefold()


def _add_license_relationship(
    graph: list[dict[str, Any]],
    package: dict[str, Any],
    license_element: dict[str, Any],
    creation_id: str,
    document_id: str,
) -> None:
    package_id = package.get("spdxId")
    license_id = license_element.get("spdxId")
    if not isinstance(package_id, str) or not isinstance(license_id, str):
        raise ValueError("package and license identifiers are required")
    if any(
        item.get("type") == "Relationship"
        and item.get("relationshipType") == "hasDeclaredLicense"
        and item.get("from") == package_id
        and item.get("to") == [license_id]
        for item in graph
    ):
        return
    suffix = hashlib.sha256(f"{package_id}|hasDeclaredLicense|{license_id}".encode("utf-8")).hexdigest()[:24]
    graph.append(
        {
            "type": "Relationship",
            "spdxId": f"{document_id}#Relationship-hasDeclaredLicense-{suffix}",
            "creationInfo": creation_id,
            "from": package_id,
            "to": [license_id],
            "relationshipType": "hasDeclaredLicense",
        }
    )
