# Pinned SPDX 3.0.1 validation assets

These read-only assets are used by `validate_spdx31_offline.py`. They are
validated locally and never fetched during generation, CI, or acceptance.

Retrieved on 2026-10-20 from official SPDX sources:

| Asset | Source | Version | SHA-256 |
|---|---|---|---|
| `spdx-json-schema.json` | [SPDX 3.0.1 JSON Schema](https://spdx.org/schema/3.0.1/spdx-json-schema.json) | SPDX 3.0.1 | `582c64e809d5b3ef9bd0c4de13a32391b47b0284a3e8d199569fb96f649234b1` |
| `spdx-model.ttl` | [SPDX 3.0.1 model](https://spdx.org/rdf/3.0.1/spdx-model.ttl) | SPDX 3.0.1 | `30ebb4af2d70a9809044ef46f44cc3dc5125226d70f818a50ed2e1d5f404c593` |
| `spdx-context.jsonld` | [SPDX 3.0.1 JSON-LD context](https://spdx.org/rdf/3.0.1/spdx-context.jsonld) | SPDX 3.0.1 | `c72b0928f094c83e5c127784edb1ebca2af74a104fcacc007c332b23cbc788bd` |
| `license-list.json` | [SPDX License List data v3.29.0](https://github.com/spdx/license-list-data/blob/v3.29.0/json/licenses.json) | License List 3.29.0 | `47d1cc681abe31166b342b6cc4aab13a6ba8ea5c48794697fe3bf8b1dbaf509a` |

The pinned License List is version 3.29.0, published 2026-09-16 according to
the [official SPDX License List](https://spdx.org/licenses/). The normalizer
maps only an explicit allowlist of unambiguous Maven POM names to list
identifiers. Unknown or ambiguous declarations are not converted into license
claims. The validator checks every emitted expression against this local list
and checks the element's `simplelicensing_licenseListVersion`.

The validator verifies JSON Schema references are local, replaces the
document's remote context URL with the pinned context object in memory, parses
the pinned SHACL model from disk, and blocks URL/network retrieval. It handles
only the SPDX model's explicit `ExternalMap` reference for the cdxgen creator;
all other SHACL violations fail validation.

## Generator used for the Windows acceptance run

The local acceptance used the official [cdxgen v13.4.0 release](https://github.com/cdxgen/cdxgen/releases/tag/v13.4.0)
Windows AMD64 asset. Its SHA-256 was checked against the official release
digest before use:

`0b4a6ec411a70127d72e2f6fc45277f03fa64d4237a40325d0ff117634b76375`

The executable is deliberately not stored in this repository. The generation
command rejects any other digest/version, requires the Maven wrapper's pinned
distribution to already be cached, validates that repository-owned
`.mvn/settings.xml` contains no server/proxy credential entries, invokes Maven
in offline runtime scope, permits only loopback hosts, scrubs secret/proxy
environment variables, and records its local execution trace.
