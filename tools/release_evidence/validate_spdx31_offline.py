#!/usr/bin/env python3
"""Validate SPDX JSON-LD 3.0.1 against pinned local schema and SHACL assets."""

from __future__ import annotations

import copy
import hashlib
import json
import socket
import sys
import urllib.request
from pathlib import Path
from typing import Any

import jsonschema
import pyshacl
import rdflib
from rdflib import Namespace, URIRef
from rdflib.namespace import RDF, SH


ASSET_DIR = Path(__file__).resolve().parent / "spdx-assets" / "3.0.1"
SCHEMA_PATH = ASSET_DIR / "spdx-json-schema.json"
MODEL_PATH = ASSET_DIR / "spdx-model.ttl"
CONTEXT_PATH = ASSET_DIR / "spdx-context.jsonld"
LICENSE_LIST_PATH = ASSET_DIR / "license-list.json"
EXPECTED_CONTEXT = "https://spdx.org/rdf/3.0.1/spdx-context.jsonld"
SPDX_CORE = Namespace("https://spdx.org/rdf/3.0.1/terms/Core/")
EXPECTED_LICENSE_LIST_VERSION = "3.29.0"
PINNED_FORMAT = "SPDX-3.0.1"


class OfflineValidationError(ValueError):
    pass


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _deny_network(*_args: Any, **_kwargs: Any) -> None:
    raise RuntimeError("SPDX acceptance validation is offline-only")


def validate_document(candidate: Path) -> dict[str, Any]:
    for asset in (SCHEMA_PATH, MODEL_PATH, CONTEXT_PATH, LICENSE_LIST_PATH):
        if not asset.is_file():
            raise OfflineValidationError(f"required pinned SPDX asset is missing: {asset.name}")

    urllib.request.urlopen = _deny_network
    urllib.request.urlretrieve = _deny_network
    socket.create_connection = _deny_network
    socket.getaddrinfo = _deny_network
    socket.socket.connect = _deny_network
    socket.socket.connect_ex = _deny_network

    document = json.loads(candidate.read_text(encoding="utf-8"))
    if document.get("@context") != EXPECTED_CONTEXT:
        raise OfflineValidationError("candidate does not declare the pinned SPDX 3.0.1 context")
    context_document = json.loads(CONTEXT_PATH.read_text(encoding="utf-8"))
    context = context_document.get("@context")
    if not isinstance(context, dict):
        raise OfflineValidationError("pinned SPDX context does not contain an inline @context")
    if any(
        isinstance(value, str) and value.startswith(("http://", "https://"))
        for key, value in context.items()
        if key == "@import"
    ):
        raise OfflineValidationError("pinned SPDX context contains a remote import")

    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    schema_refs: list[str] = []

    def collect_refs(value: Any) -> None:
        if isinstance(value, dict):
            reference = value.get("$ref")
            if isinstance(reference, str):
                schema_refs.append(reference)
            for nested in value.values():
                collect_refs(nested)
        elif isinstance(value, list):
            for nested in value:
                collect_refs(nested)

    collect_refs(schema)
    if any(not reference.startswith("#") for reference in schema_refs):
        raise OfflineValidationError("pinned JSON Schema contains an external reference")

    validator_type = jsonschema.validators.validator_for(schema)
    validator_type.check_schema(schema)
    schema_errors = sorted(
        validator_type(schema).iter_errors(document),
        key=lambda error: (list(map(str, error.absolute_path)), error.message),
    )
    if schema_errors:
        return {
            "result": "FAIL",
            "format": PINNED_FORMAT,
            "schemaErrors": [
                {"path": "/".join(map(str, error.absolute_path)), "message": error.message}
                for error in schema_errors[:30]
            ],
            "schemaErrorCount": len(schema_errors),
        }

    license_list = json.loads(LICENSE_LIST_PATH.read_text(encoding="utf-8"))
    if license_list.get("licenseListVersion") != EXPECTED_LICENSE_LIST_VERSION:
        raise OfflineValidationError("pinned SPDX License List version does not match the validator profile")
    license_ids = {
        item.get("licenseId")
        for item in license_list.get("licenses", [])
        if isinstance(item, dict) and isinstance(item.get("licenseId"), str)
    }
    for item in document.get("@graph", []):
        if item.get("type") != "simplelicensing_LicenseExpression":
            continue
        expression = item.get("simplelicensing_licenseExpression")
        if (
            not isinstance(expression, str)
            or expression not in license_ids
            or item.get("simplelicensing_licenseListVersion") != EXPECTED_LICENSE_LIST_VERSION
        ):
            return {
                "result": "FAIL",
                "format": PINNED_FORMAT,
                "schemaErrorCount": 0,
                "licenseError": f"unrecognized or unversioned SPDX license expression: {expression!r}",
            }

    expanded_document = copy.deepcopy(document)
    expanded_document["@context"] = context
    data_graph = rdflib.Graph()
    data_graph.parse(data=json.dumps(expanded_document), format="json-ld")
    shacl_graph = rdflib.Graph()
    shacl_graph.parse(str(MODEL_PATH), format="turtle")
    conforms, report_graph, report_text = pyshacl.validate(
        data_graph,
        shacl_graph=shacl_graph,
        ont_graph=shacl_graph,
        inference="none",
        do_owl_imports=False,
    )
    external_ids = {
        str(value)
        for value in data_graph.objects(None, SPDX_CORE.externalSpdxId)
    }
    violations: list[rdflib.term.Node] = []
    filtered_external_agents = 0
    for result in report_graph.subjects(RDF.type, SH.ValidationResult):
        if report_graph.value(result, SH.resultSeverity) != SH.Violation:
            continue
        value = report_graph.value(result, SH.value)
        source_shape = report_graph.value(result, SH.sourceShape)
        expected_class = report_graph.value(source_shape, SH["class"]) if source_shape else None
        result_path = report_graph.value(result, SH.resultPath)
        focus_node = report_graph.value(result, SH.focusNode)
        is_external_creator = (
            isinstance(value, URIRef)
            and str(value) in external_ids
            and str(result_path) == str(SPDX_CORE.createdBy)
            and expected_class == SPDX_CORE.Agent
            and isinstance(focus_node, URIRef)
            and str(focus_node).startswith("urn:cdxgen:spdx:")
            and str(focus_node).endswith("#CreationInfo-main")
        )
        if is_external_creator:
            filtered_external_agents += 1
        else:
            violations.append(result)
    if not conforms and violations:
        return {
            "result": "FAIL",
            "format": PINNED_FORMAT,
            "schemaErrorCount": 0,
            "shaclReport": report_text[:20000],
            "unfilteredViolationCount": len(violations),
        }

    return {
        "result": "PASS",
        "format": PINNED_FORMAT,
        "schema": {
            "source": "official SPDX 3.0.1 JSON Schema",
            "sha256": _sha256(SCHEMA_PATH),
            "internalReferences": len(schema_refs),
        },
        "shacl": {
            "source": "official SPDX 3.0.1 model",
            "sha256": _sha256(MODEL_PATH),
            "triples": len(data_graph),
            "nodes": len(set(data_graph.subjects())),
            "externalCreatorReferenceViolationsHandled": filtered_external_agents,
        },
        "contextSha256": _sha256(CONTEXT_PATH),
        "licenseList": {
            "version": EXPECTED_LICENSE_LIST_VERSION,
            "sha256": _sha256(LICENSE_LIST_PATH),
            "identifierCount": len(license_ids),
        },
        "sbomSha256": _sha256(candidate),
        "sbomBytes": candidate.stat().st_size,
    }


def main(argv: list[str] | None = None) -> int:
    if argv is None:
        argv = sys.argv[1:]
    if len(argv) != 1:
        print("usage: validate_spdx31_offline.py SPDX_FILE", file=sys.stderr)
        return 2
    try:
        report = validate_document(Path(argv[0]))
    except (OSError, json.JSONDecodeError, OfflineValidationError, ValueError) as error:
        print(json.dumps({"result": "FAIL", "error": str(error)}, sort_keys=True))
        return 2
    print(json.dumps(report, sort_keys=True))
    return 0 if report.get("result") == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
