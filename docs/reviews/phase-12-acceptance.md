# Phase 12 Acceptance Record

**Result:** PASS — Phase 12 implementation is frozen locally at `82668b7e3262e9783e6ab58cb8037a1dc77e8e06`.
**Scope:** Aggregate Analytics and downstream DHIS2 integration through the existing Phase 9 secure-exchange boundary. Phase 12 does not alter operational ownership or export individual-level health data.

## Approved ticket graph

The human-approved nine-slice graph is stored as local Markdown under `.scratch/phase-12-public-health-analytics-dhis2/issues/`:

| Ticket | Outcome | Dependencies |
|---|---|---|
| P12-01 | Referral-owned bounded reporting snapshots | — |
| P12-02 | Transfer-owned bounded reporting snapshots with attribution | — |
| P12-03 | Versioned Analytics computation and reproducible snapshots | P12-01, P12-02 |
| P12-04 | Immutable late-data revisions and authorized correction | P12-03 |
| P12-05 | Shared disclosure-lineage and reservation gate | P12-04 |
| P12-06 | Versioned DHIS2 mapping and one-value aggregate export through Phase 9 | P12-05 |
| P12-07 | Complete-tuple single-flight barrier and unknown-outcome recovery | P12-06 |
| P12-08 | Named compatible aggregate federation through the shared disclosure gate | P12-05 |
| P12-09 | Integrated Phase 12 and Volume IV acceptance | P12-07, P12-08 |

No external issue tracker was used. Work was integrated sequentially where aggregate history, disclosure lineage, and target-tuple ordering require it.

## Ownership and reporting inputs

Analytics owns indicator definitions and versions, aggregate snapshots/revisions, completeness, disclosure decisions/reservations, DHIS2 mapping versions, and export outcomes. Its schema is `analytics`. It has no operational source facts, patient records, or direct access to private owner persistence.

The implemented owner contracts are deliberately bounded:

- Referral provides `SUBMITTED_COUNT` and `RESOLVED_COUNT` for an authorized organization/facility scope and half-open period.
- Transfer provides `TRANSFER_ACCEPTED`, `TRANSFER_COMPLETED`, and `TRANSFER_UNFULFILLED` with explicit origin/destination attribution; an unsupported unfulfilled destination attribution is rejected.
- Capacity and Transport do not currently expose Phase 12 reporting measures. Their potential measures remain outside the shipped catalogue until an approved owner contract and indicator require them.

Both contracts require the dedicated Analytics service identity and return aggregates, source cutoff/version, completeness and generation time. They return no operational rows, case IDs, or patient data. Transfer numerator/denominator attribution must agree for scalar indicators; transfer attribution dimensions are required where the formula uses Transfer measures. Regression tests cover those rules.

## Computation, time and revisions

Indicator definitions are immutable by version. Reporting periods use an explicit IANA timezone and `[start,end)` boundaries, with resolved UTC instants persisted. The period resolver does not use the host default timezone. Snapshots retain their indicator/version, period/scope, bounded owner inputs and cutoffs, completeness, computation time, attribution, result and revision identity.

`COMPLETE`, `PARTIAL`, `MISSING_SOURCE`, `STALE_SOURCE`, and `NOT_APPLICABLE` remain distinct as applicable. A complete, definition-approved empty count may be zero; an unavailable or incomplete owner source is never converted to zero. Late facts and authorized corrections create immutable linked revisions with cause/audit; historical snapshots and export operations are retained.

## Disclosure gate

DHIS2 export and aggregate federation use the same durable Analytics disclosure gate. Stable disclosure lineages retain release history across policy/mapping version changes; related cells, totals, periods, revisions, and in-flight or unknown outcomes are considered together. PostgreSQL locks are acquired in stable order and the reservation commits before network I/O. The database transaction is not held open during exchange. `OUTCOME_UNKNOWN` remains potentially disclosed. A reservation is withdrawn only when the exchange boundary proves no request bytes could have left AyuVahak.

PostgreSQL-backed tests cover complementary suppression, overlapping periods, revision differencing, prior releases, stable lineages across policy rollover, merge/split history, exact duplicate recovery, changed fingerprints, and serialization between DHIS2 and peer publication. Automated dimensional coarsening is not implemented; where an unsafe/ambiguous disclosure cannot be safely reviewed, the release is withheld/fails closed. This is the safe configured behavior, not an automatic release path.

## DHIS2 profile and recovery

The adapter maps approved indicator/version, organization/facility, period, bounded dimensions and partner configuration to preconfigured DHIS2 metadata. It sends exactly one aggregate DataValue in each synchronous DataValueSet request through Phase 9. It creates no DHIS2 metadata, OAuth clients, users, programs or categories and has no caller-supplied URL. It implements no Tracker/Event profile.

The complete DHIS2 target tuple includes the configured partner, data element, org unit, period, category option combination, and attribute option combination where configured. A durable PostgreSQL claim permits only one unresolved operation per tuple. A newer revision cannot overtake an in-flight or unknown older write, including across restart. `imported=1`/`updated=1` are recorded as acknowledgements for the configured profile; `ignored=1` remains ambiguous, asynchronous job results are unsupported, and an uncertain response is not blindly retried. Authorized read-back or operator reconciliation resolves the barrier. Numeric zero requires complete evidence and explicit target zero semantics; omission is not deletion and ordinary deletion is not exposed.

Local deterministic TLS/mock tests cover successful value acknowledgement, authentication/authorization and validation failures, ambiguous ignored results, unknown outcome after synthetic commit, restart recovery, duplicate/fingerprint behavior, partner revocation, mapping versioning, complete tuple fencing, and stale revision blocking. All Phase 9 endpoint, TLS, secret and transport safeguards are reused.

## Aggregate federation

P12-08 is implemented because the frozen specification includes the optional aggregate-to-aggregate contract. Only compatible, approved aggregate snapshots are exchanged with a trusted peer through Phase 9 and the shared disclosure gate. Source deployment, indicator fingerprint/version, period, attribution, dimensions, completeness, revision and stable snapshot identity are retained. Exact duplicates recover their outcome; changed fingerprints conflict. No owner-level rows or raw operational facts are sent.

## Privacy, architecture and audit

Architecture tests enforce owner/application boundaries, no owner-private persistence imports, no patient/IPS dependencies in Analytics, DHIS2 adapter isolation, and Phase 9 transport reuse. Analytics results do not feed back into operational contexts. Audit records retain administrative and export attribution without payload archival. Metrics and logs use bounded categories and omit patient/case IDs, target-tuple identifiers and credentials.

The synthetic marker `SYN-PATIENT-PHASE12-PRIVATE-91F4` is exercised in privacy tests. Domain/message assertions prove it is not included in federated aggregate messages. Export, reporting and persistence contracts are aggregate-only by type and boundary. The verification did not run a single exhaustive marker scan over every runtime log, metrics backend, trace backend and Problem Details instance; those surfaces are additionally constrained by the accepted architecture and safe observability tests, and are recorded as a limitation rather than claimed as a universal runtime sweep.

## Verification

Canonical command: `\.\mvnw.cmd -B -ntp clean verify`

Result: **BUILD SUCCESS** on Eclipse Temurin Java 25.0.4.1, Apache Maven 3.10.0, Spring Boot 4.1.1. Surefire ran 440 tests and Failsafe ran 396 tests; failures, errors, and skips were all zero. Flyway successfully applied 90 migrations. The final clean run followed a formatting-only Spotless correction; the final run itself passed formatting.

Testcontainers and controlled local TLS/HTTP fixtures were used for database, identity and DHIS2/peer behavior. No public DHIS2, healthcare, government or federation endpoint was used. The final Compose smoke used isolated local infrastructure and synthetic settings. The application started, applied all 90 migrations, and returned `UP` from `127.0.0.1:18880/actuator/health`; the isolated project and its volumes were then removed. The observed schema set included `analytics`, `audit`, `capacity`, `directory`, `identity_access`, `matching`, `platform_eventing`, `platform_exchange`, `platform_federation`, `platform_notification`, `referral`, `transfer`, and `transport` (plus PostgreSQL `public`). The audit found zero cross-schema foreign keys.

`pom.xml` and `compose.yaml` are unchanged from the Phase 12 starting baseline. No new Phase 12 dependency, DHIS2 SDK, broker, Redis, warehouse, Tracker client, or cloud analytics SDK was added. The complete dependency audit is retained in the untracked local planning/build artifacts and was not committed.

## Independent review

Corrected Phase 12 specification and standards reviews found zero unresolved BLOCKER and zero unresolved MAJOR findings. Final combined Volume IV reviews also found zero BLOCKER and zero MAJOR. Review notes were:

- **MINOR:** automated dimensional coarsening is not implemented; unsafe/ambiguous release remains withheld or requires review, preserving fail-closed behavior.
- **MINOR:** some Analytics service paths repeat authentication-mechanism-to-audit-evidence conversion; no behavior or boundary defect was identified.
- **DOCUMENTATION:** contributor guidance had stale schema/program status; corrected in the Volume IV retro changes.

No required pre-freeze issue remains. Phase 12 passed its acceptance gate and was frozen locally.

## Scope and safety

- No public/shared DHIS2, public healthcare system, ministry service, or external identity provider was used as a test target.
- No external mutation, metadata creation, OAuth registration, deployment, publication, or remote Git write occurred.
- Only synthetic data and local controlled infrastructure were used.
- Patient-level Tracker/Event export, patient identifiers, clinical records and Volume V work were not implemented.
