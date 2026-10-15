# Phase 8 IPS Profile Package Preflight

**Checked:** 2026-10-15
**Question:** Which official International Patient Summary (IPS) profile package should Phase 8 use, what does it depend on, and can HAPI load it without runtime access to a public service?
**Scope:** Initial findings came from read-only inspection of official HL7 and HAPI sources. No public FHIR endpoint, SMART sandbox, terminology service, or healthcare system was contacted. The implementation later added only the curated offline profile subsets listed in [`PROFILE_ARTIFACTS_NOTICES.md`](../../src/main/resources/org/ayuvahak/platform/fhir/profiles/PROFILE_ARTIFACTS_NOTICES.md); no full terminology package or remote runtime resolver is used.

## Findings

- The current published IPS Implementation Guide is **STU 2, version 2.0.1**, package id `hl7.fhir.uv.ips`, based on FHIR R4 `4.0.1`. HL7’s published QA metadata identifies 2.0.1 as the current version and describes it as an errata release over 2.0.0. The corresponding canonical package coordinate is `hl7.fhir.uv.ips#2.0.1`. Sources: [IPS 2.0.1 QA report](https://hl7.org/fhir/uv/ips/2.0.1/qa.html), [IPS 2.0.1 copyrights/publication page](https://hl7.org/fhir/uv/ips/2.0.1/en/copyrights.html).
- The IG is licensed under Creative Commons **CC0**. However, HL7 explicitly cautions that referenced third-party terminology artifacts may require separate licenses. Inclusion of the IPS package alone therefore does not establish that every terminology expansion or code system may be redistributed or used without further review. Sources: [IPS 2.0.1 copyrights](https://hl7.org/fhir/uv/ips/2.0.1/en/copyrights.html), [FHIR R4 license](https://hl7.org/fhir/R4/license.html).
- The published 2.0.1 QA dependency table names these required packages for R4 validation:

  | Package | Version | Role in package metadata |
  | --- | --- | --- |
  | `hl7.fhir.uv.ips` | `2.0.1` | IPS profiles and conformance artifacts |
  | `hl7.terminology.r4` | `7.2.0` | Mandatory terminology dependency |
  | `hl7.fhir.uv.extensions.r4` | `5.3.0` | Mandatory R4 extensions dependency |
  | `hl7.fhir.uv.ipa` | `1.1.0` | Mandatory IPA dependency |
  | `hl7.fhir.uv.tools.r4` | `1.1.2` | Mandatory R4 tooling dependency |

  These are FHIR NPM conformance packages, not Maven library coordinates. Pin and load the exact package set locally; do not let validation retrieve dependencies or terminology from a remote registry/service at runtime. The dependency list is recorded by the [official IPS QA report](https://hl7.org/fhir/uv/ips/2.0.1/qa.html).
- HAPI provides `NpmPackageValidationSupport.loadPackageFromClasspath(...)`, which reads a local classpath NPM `.tgz` package and loads conformance resources into validation support. HAPI's official example constructs an R4 `FhirContext`, loads a package from classpath, and chains package support with default R4 profiles, local terminology support, and snapshot generation. The selected HAPI release has been exercised against the exact curated local profile artifacts in positive/negative synthetic tests; those tests do not imply the omitted upstream dependency packages or complete terminology coverage. Sources: [HAPI `NpmPackageValidationSupport`](https://github.com/hapifhir/hapi-fhir/blob/master/hapi-fhir-validation/src/main/java/org/hl7/fhir/common/hapi/validation/support/NpmPackageValidationSupport.java), [HAPI validator package example](https://github.com/hapifhir/hapi-fhir/blob/master/hapi-fhir-docs/src/main/java/ca/uhn/hapi/fhir/docs/ValidatorExamples.java).
- HAPI R4 core resources alone do not establish IPS profile conformance. Current evidence is narrower: the exact pinned Patient and Bundle profiles and selected IPS resource constraints are loaded locally and exercised by synthetic tests. Do not describe this as complete IPS conformance; report bounded structural/profile coverage and the indeterminate terminology result.

## Phase 8 implementation outcome and limits

The parser uses local HAPI NPM package loading for a curated offline subset: 32 IPS StructureDefinitions (from the upstream 74 indexed entries), six directly referenced R4 extension StructureDefinitions (from 885 entries), and the R4 core support already provided by HAPI. It does not bundle the full mandatory `hl7.terminology.r4`, IPA, or tools packages. Startup verifies the pinned Patient and Bundle profiles are present at version 2.0.1 and warms validation within a bounded startup-only budget; requests retain the configured payload-processing budget. Local positive/negative tests exercise IPS Patient and `Bundle-uv-ips`: a synthetic document Bundle passes, while a non-document collection Bundle fails. Artifact provenance, licensing, selected contents, and checksums are recorded in [`PROFILE_ARTIFACTS_NOTICES.md`](../../src/main/resources/org/ayuvahak/platform/fhir/profiles/PROFILE_ARTIFACTS_NOTICES.md).

This deliberately bounded support is not full guide conformance. Terminology coverage is explicitly `INDETERMINATE`; absent or unsupported safety-relevant terminology cannot be treated as valid based on display text. No package registry or terminology service is contacted at runtime. Missing pinned artifacts/profile definitions fail parser initialization rather than falling back to core-only validation.

The required packages contain a broad IPS conformance surface, while P8-04 is a narrow, transient disclosure flow. Do not interpret loading the package as authorization to persist or return a whole IPS document. Referral must still perform recipient, grant, purpose, and deployment-policy checks; required audit must succeed before retrieval/disclosure; raw payloads remain transient; and the returned projection must remain explicitly allowlisted. A valid IPS profile establishes structural/profile conformance only, not patient identity match, clinical truth, freshness, disclosure authority, or operational suitability.

## Research and implementation limits

This note records official package metadata and the implementation's local classpath validation evidence. Curated artifact provenance, exact source and shipped checksums, license notices, included/excluded counts, and known dependency/terminology limitations are in [`PROFILE_ARTIFACTS_NOTICES.md`](../../src/main/resources/org/ayuvahak/platform/fhir/profiles/PROFILE_ARTIFACTS_NOTICES.md). The tests do not establish full IPS guide or terminology conformance. No package registry, terminology service, or public healthcare target is used at runtime or in tests.

## HAPI versioned core-reference compatibility

The pinned IPS Composition profile names R4 core target profiles with versioned
canonicals such as `Condition|4.0.1` and `DocumentReference|4.0.1`. A local
synthetic IPS Bundle test reproduced HAPI FHIR 8.8.2 validation errors
`Reference_REF_CantResolveProfile` and `Reference_REF_BadTargetType` for those
references, even though the referenced IPS Condition resource and each entry's
fullUrl/profile were valid locally. The related [HAPI issue #7453](https://github.com/hapifhir/hapi-fhir/issues/7453)
reports this versioned core-reference resolution behavior in HAPI 8.6.0.

The Phase 8 compatibility support therefore resolves only canonical URLs under
`http://hl7.org/fhir/StructureDefinition/` whose requested version is exactly
`4.0.1`. It asks the existing local R4 core support for the unversioned
canonical and accepts the result only when its `StructureDefinition.version`
is exactly `4.0.1`. Other core versions fail closed. IPS, mCSD, and other
implementation-guide canonicals are passed through unchanged, preserving their
exact versions. Regression tests cover both the R4 core version guard and the
unchanged guide-profile lookup. This is a narrow local validator compatibility
layer; it does not enable remote profile resolution or relax IPS profile
validation.
