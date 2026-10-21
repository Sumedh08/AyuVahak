# Phase 6 Implementation Review — Destination Matching and Recommendation

**Result:** PASS. Phase 6 is locally frozen. Phase 7 is authorized only after this freeze and is not started by this record.

## Delivered scope

Matching evaluates one exact submitted Referral revision against bounded Directory evidence, a bounded Capacity readiness batch, and one versioned Matching policy. It reports explicit `ELIGIBLE`, `INELIGIBLE`, and `INDETERMINATE` outcomes; ranks only eligible candidates deterministically; stores immutable, structured Recommendation evidence; and revalidates applicability synchronously before a snapshot can be used to initiate a later Transfer request. Matching remains advisory. It does not select a destination, accept a request, reserve capacity, or implement Transfer/Transport.

The Matching-owned CareCapability-to-CapacityDimension mapping remains versioned and deployment-configurable. Directory, Capacity, and Referral retain their state and expose bounded owner contracts. Cross-context reads use the dedicated Matching service identity and exact configured grants; Matching does not impersonate the initiating human or read another context's persistence directly.

## Ticket graph

Seven local tickets were completed sequentially: policy/authority; owner-read contracts; eligibility; ranking/evidence; idempotent recommendation command; history/applicability; integrated acceptance and boundary verification. The dependency order is recorded in the [local Phase 6 graph](../../.scratch/phase-6-matching-recommendation/map.md). The slices were sequential because each depended on contract or state semantics established by the previous slice. None ran concurrently. No future context placeholders were added.

## Material versions

| Component | Version |
| --- | --- |
| Java | Eclipse Temurin `25.0.4.1+1` |
| Spring Boot | `4.1.1` |
| Maven Wrapper | Apache Maven `3.10.0` |
| Flyway Community | `12.4.0` |
| PostgreSQL/PostGIS | `postgis/postgis:18-3.6`, pinned by digest; runtime PostgreSQL `18.6`, PostGIS `3.6.4` |
| Testcontainers | `2.0.5` |
| ArchUnit | `1.5.1` |
| Local reference identity provider | Keycloak `26.8.0`, pinned by digest |

Phase 6 introduced no dependency or runtime service beyond the frozen platform baseline. Migration `V19` creates only Matching-owned recommendation state. No Transfer schema is present.

## Evidence review and remediation

The first independent specification review identified a material evidence gap: a Recommendation snapshot retained only the latest Capacity observation and did not preserve the Capacity projection's reason codes, `fallbackUsed`, or every selected source observation. The Phase 6 integrated HTTP regression was written first and failed because `fallbackUsed` was absent. The implementation now carries minimized Capacity projection details in the immutable snapshot and API representation: projection reasons, fallback selection, and all selected source references, source/version metadata, values, observation times, validity, and reason codes. It does not copy source payloads. The test verifies the API response, retrieved history, and persisted JSON with synthetic conflicting observations. The final specification-axis review found no remaining actionable findings.

The standards review found no production ownership, privacy, or module-boundary violation. It found two stale contributor-document statements about implemented schemas and HTTP contexts; both were corrected in [database and migrations](../development/database-and-migrations.md) and [HTTP API conventions](../development/http-api.md). It also noted repeated adapter-level unavailable-error mapping as a low-priority maintainability observation; no correctness defect was found and no unrelated refactor was made.

| Classification | Count | Status |
| --- | ---: | --- |
| BLOCKER | 0 | — |
| MAJOR | 1 | Resolved: full Capacity projection evidence is preserved and tested. |
| MINOR | 1 | Recorded: repeated adapter-level failure mapping; no behavior change required for this freeze. |
| DOCUMENTATION | 2 | Resolved: schema and HTTP context descriptions updated. |

## Boundaries and limitations

- No direct cross-context persistence access was introduced; the 11 production ArchUnit rules ran in the normal Maven verification lifecycle.
- Recommendations contain no `PatientReference`, clinical narrative, demographics, or full Referral payload.
- The optional travel-time port has no external provider; local matching uses the specified deterministic distance behavior and explicit source/fallback labels.
- Transfer, Acceptance, Reservation, Transport, EMS, FHIR, DHIS2, AI/ML, and public routing integrations remain outside this phase.
- The implementation follows the frozen Phase 0 architecture and Phase 6 specification. The one specification clarification records the evidence already returned by Capacity and does not change the ownership model.

## Verification

The full clean verification ran with Java `25.0.4.1+1`, Maven `3.10.0`, the pinned Testcontainers PostGIS image, and local Docker. `clean verify` passed with 137 Surefire tests and 151 Failsafe tests; zero failures, errors, or skips. Spotless checked 294 Java files with no changes required. The added targeted cross-phase regression also passed separately: 1 `DirectoryCapacityReferralScenarioIT`, 0 failures/errors/skips.

`docker compose config --quiet` passed. `docker compose up --build --detach` built and started the local app, PostGIS, and Keycloak. After readiness, local GET probes returned HTTP 200 for aggregate health, liveness, readiness, `/api/v1/system-info`, `/actuator/prometheus`, and Keycloak discovery. The read-only schema query showed the Matching schema and no Transfer schema. `docker compose down` stopped the local stack without deleting its database volume. Local ports `18080`/`18081` were used because the default app port was unavailable; the ignored `.env` was not added to Git.

The Phase 6 freeze is local-only commit `f0d92474c67d07dd969b817f429a75b6845c545f`. Its hash is recorded in the [acceptance evidence](phase-6-acceptance.md) by a subsequent documentation-only commit.
