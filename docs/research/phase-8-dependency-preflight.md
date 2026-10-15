# Phase 8 FHIR Dependency Preflight

**Retrieved:** 2026-10-15
**Scope:** Read-only check of official HAPI FHIR material and Maven Central metadata for a minimal R4 model/parser and profile-validation dependency set. This note informs Phase 8 implementation; it does not change the frozen architecture or select a FHIR server. No healthcare endpoint, sandbox, or running FHIR service was contacted.

## Verified facts

### R4 model and parser

- HAPI's module guide identifies `hapi-fhir-structures-r4` as its FHIR R4 model module and `hapi-fhir-base` as the core library containing `FhirContext`, parsers, and support classes. The importing guide says an application needs the core JAR plus a structures JAR; the R4 structures POM declares `hapi-fhir-base` as a dependency. Thus the application can declare only `ca.uhn.hapi.fhir:hapi-fhir-structures-r4` for R4 model and JSON parser APIs; Maven brings in the required base module. Sources: [HAPI module guide](https://hapifhir.io/hapi-fhir/docs/getting_started/modules.html), [HAPI import guide](https://hapifhir.io/hapi-fhir/docs/getting_started/downloading_and_importing.html), [R4 structures POM at v8.12.1](https://github.com/hapifhir/hapi-fhir/blob/v8.12.1/hapi-fhir-structures-r4/pom.xml).
- HAPI documents parsing with `FhirContext.forR4()` and `newJsonParser()`. It distinguishes parser-time structural/error handling from deeper instance validation; parsing alone does not establish profile conformance. Source: [HAPI parsing and serialization](https://hapifhir.io/hapi-fhir/docs/model/parsers.html), [HAPI validation introduction](https://hapifhir.io/hapi-fhir/docs/validation/introduction.html).

### Profile validation modules

- HAPI's separate `hapi-fhir-validation` module provides the FHIR Profile Validator. `hapi-fhir-validation-resources-r4` provides R4 StructureDefinitions, ValueSets, CodeSystems, schemas, and other validation artifacts. The official validator guide says that validating against the built-in official R4 definitions requires the matching validation-resources JAR. For an additional implementation guide, HAPI documents loading its package into a local validation-support chain; that package can be a pinned local fixture rather than something fetched from a public service. Sources: [HAPI module guide](https://hapifhir.io/hapi-fhir/docs/getting_started/modules.html), [HAPI validator guide](https://hapifhir.io/hapi-fhir/docs/validation/instance_validator.html), [HAPI validation support modules](https://hapifhir.io/hapi-fhir/docs/validation/validation_support_modules.html).
- The narrow HAPI dependency set for both parsing and official R4/profile validation is therefore:

  ```text
  ca.uhn.hapi.fhir:hapi-fhir-structures-r4
  ca.uhn.hapi.fhir:hapi-fhir-validation
  ca.uhn.hapi.fhir:hapi-fhir-validation-resources-r4
  ```

  Keep all three on one pinned HAPI release. A project-specific profile package is additional validation data, not a reason to add a FHIR server.
- The v8.12.1 validation POM marks version-specific model and validation-resource modules optional, but has a non-optional `hapi-fhir-structures-r5` dependency; its source comment says the validator and snapshot generation require R5 structures independent of the target version being validated. This means R4-only validation still has an R5 model-library transitive footprint. It does not mean AyuVahak should accept or parse R5. Source: [HAPI validation POM at v8.12.1](https://github.com/hapifhir/hapi-fhir/blob/v8.12.1/hapi-fhir-validation/pom.xml).

### Release and compatibility evidence

- The official HAPI GitHub releases page marks `v8.8.2` as “Latest”, released 2026-09-25. Maven Central's artifact metadata reports `8.12.1` as its highest/latest coordinate, but that release was dated 2026-09-15 on the same official releases page. HAPI is publishing parallel numbered release lines; choosing by the numeric maximum alone would not match the upstream-designated current release. Phase 8 pins `8.8.2`, the release explicitly marked Latest by HAPI at research time. Sources: [official HAPI releases](https://github.com/hapifhir/hapi-fhir/releases), [Maven Central metadata for R4 structures](https://repo.maven.apache.org/maven2/ca/uhn/hapi/fhir/hapi-fhir-structures-r4/maven-metadata.xml).
- The tagged v8.8.2 root POM sets `maven.compiler.release` to 17. Its bytecode baseline is compatible with a newer JVM in principle, but HAPI does not claim Java 25 certification. Phase 8 therefore verifies the pinned artifacts by compiling and running the focused parser/validator tests on AyuVahak's Java 25 baseline. Source: [HAPI v8.8.2 root POM](https://github.com/hapifhir/hapi-fhir/blob/v8.8.2/pom.xml).
- The selected model/parser and validation modules are ordinary Java libraries and require no Spring Boot integration. HAPI's v8.8.2 root POM manages Spring Boot 3.4.11; this is not evidence of compatibility with AyuVahak Boot 4.1.x. No HAPI Spring Boot starter/autoconfiguration is selected. HAPI publishes no explicit Boot 4.1 compatibility claim for these core modules. Source: [HAPI v8.8.2 root POM](https://github.com/hapifhir/hapi-fhir/blob/v8.8.2/pom.xml).
- HAPI declares Apache Software License 2.0 in the tagged root POM. Transitive dependency licenses still require the normal repository dependency/license audit. Sources: [HAPI v8.8.2 root POM](https://github.com/hapifhir/hapi-fhir/blob/v8.8.2/pom.xml), [HAPI v8.8.2 LICENSE.txt](https://github.com/hapifhir/hapi-fhir/blob/v8.8.2/LICENSE.txt).

## Footprint and AyuVahak recommendation

- The official module catalog separates model/parser, validation, client, plain-server, and JPA-server modules. `hapi-fhir-structures-r4`, `hapi-fhir-validation`, and `hapi-fhir-validation-resources-r4` are not server/JPA-server modules. HAPI v8.8.2's validator requires R5 structures internally even for R4 validation. Its current transitive tree also contains the HAPI converter plus R4B, DSTU2, DSTU2016May, DSTU3, and version-neutral validator model artifacts. These are brought in by the selected generic validator rather than used as accepted payload releases; the runtime tree must be reviewed, and AyuVahak must continue to reject non-R4 configuration. No FHIR R5 validation-resource package, HAPI server, JPA server, search, or client module is selected. This is a material dependency footprint and a documented trade-off of using HAPI's maintained instance validator instead of weakening profile validation. Sources: [HAPI module guide](https://hapifhir.io/hapi-fhir/docs/getting_started/modules.html), [HAPI v8.8.2 validation POM](https://github.com/hapifhir/hapi-fhir/blob/v8.8.2/hapi-fhir-validation/pom.xml).
- **Recommendation (inference):** HAPI is a practical fit if Phase 8 needs both generated R4 types/JSON parsing and profile validation: use only the three coordinates above, omit the HAPI client, Spring Boot starter, server/JPA server, and all non-R4 validation-resource modules; load any required implementation-guide package from a pinned local fixture; and retain the Phase 8 rule that FHIR classes stay in adapters. The validator's transitive R5 structures are a material cost and should be reviewed in the actual dependency tree.
- **Version decision:** use the exact stable release `8.8.2`, based on HAPI's official Latest release designation and date. Keep the HAPI modules on that same version. The result is not a claim that HAPI officially supports Java 25 or Spring Boot 4.1; compatibility is established only if the repository's Java 25 build and runtime tests pass. Do not use snapshots or a HAPI Spring Boot starter.

### Source-version ordering

- FHIR R4 `meta.versionId` is an opaque per-resource version identifier with no fixed chronological, numeric, or lexical order. Equality can identify the same version; comparing strings or numbers cannot establish which version is newer. FHIR history responses provide a source-defined sequence for one resource; mCSD ITI-91 uses history and `_since`, where boundary repeats are possible and precision may be limited. ITI-130 does not create a global revision order. Directory must use only a trusted source-defined ordering contract; if order is unavailable, preserve the current projection and report conflict/indeterminate rather than infer from text, arrival order, timestamps, or absence from a partial result. Sources: [FHIR R4 Resource metadata](https://hl7.org/fhir/R4/resource.html#Meta), [FHIR R4 history](https://hl7.org/fhir/R4/http.html#history), [IHE mCSD ITI-91](https://profiles.ihe.net/ITI/mCSD/ITI-91.html), [IHE mCSD ITI-130](https://profiles.ihe.net/ITI/mCSD/ITI-130.html).

## Limits

This was read-only documentation/source/metadata research. No dependency was downloaded or added, no repository code or specification was changed, and no public or running healthcare system was accessed. HAPI's formal support policy and an explicit Java 25 / Spring Boot 4.1 compatibility statement were not found in the sources inspected.
