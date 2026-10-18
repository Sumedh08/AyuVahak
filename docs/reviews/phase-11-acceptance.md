# Phase 11 Acceptance Record

**Result:** PASS — Phase 11 implementation is ready to freeze.
**Scope:** Real-time operations, resilience and federation only. Phase 12 and Volume V were not started.

## Ticket graph

The approved local graph contains eight vertical slices, stored only under `.scratch/phase-11-real-time-resilience-federation/issues/`:

| Ticket | Outcome | Dependencies |
|---|---|---|
| P11-01 | Bounded, scoped notification projection | — |
| P11-02 | Authorized SSE hint feed | P11-01 |
| P11-03 | Stable deployment identity and scoped peer trust | — |
| P11-04 | Owner-local federated operation identity and receipt recovery | P11-03 |
| P11-05 | Federated Directory discovery projection | P11-04 |
| P11-06 | Fresh, attributable remote Capacity evidence | P11-04 |
| P11-07 | Federated receiving coordination and remote reservation | P11-04, P11-05, P11-06 |
| P11-08 | Integrated resilience, security and acceptance | P11-02, P11-07 |

The graph was implemented sequentially where state and ownership semantics required it. No sub-agent implementation or worktree was used. Ticket files are local planning artifacts and are not part of the product commit.

## Notification delivery

The existing Phase 2 outbox remains the durable internal event substrate. A bounded PostgreSQL notification projection stores only scope, event identity, notification type, resource type and opaque ID, owner version, sequence/cursor, correlation metadata and timestamps. It does not store patient content or full event payloads. Duplicate event delivery is deduplicated; sequence allocation and replay are scoped; expiry or cursor-epoch mismatch requires an authorized full refresh.

Spring MVC SSE sends only non-authoritative resource/version hints. The client must fetch current state through the normal owner API, which independently checks authorization. Reconnect cursors are accepted from the event header, not URL parameters. Open streams reauthorize on a bounded interval and close after revocation. Queue, replay, connection and executor limits are bounded; same-resource hints may coalesce and overflow triggers refresh/disconnect. Tests cover hint minimization, grant revocation, blocked client writes, queue coalescing/overflow, connection caps, expired cursor behavior, projection reset, cross-scope isolation, concurrent sequencing, redelivery and recovery after constructing a new reader against the same PostgreSQL state.

PostgreSQL `LISTEN`/`NOTIFY` is not required for correctness and was not added. Durable projection/outbox polling is the recovery mechanism. Notification failure does not invalidate an owner commit; an authorized read returns current owner state.

## Federation and ownership

Federation uses Phase 9 configured trust and exchange boundaries. Peer identity is a deployment identity, separate from an organization or facility. Peer permission is scoped by operation, direction, context and organization/facility; authenticated peer identity does not bypass owner authorization. Remote references are namespaced by deployment, context and resource ID and have no cross-database foreign keys.

Directory retains remote source authority and provenance, source version and freshness; partial or failed reads cannot infer deletion. Matching consumes remote Capacity only as attributed evidence with source policy and freshness. A Capacity owner response now includes its configured maximum observation age, and the receiving endpoint clamps evidence `validUntil` to that policy even when a remote source supplies a later expiry. A regression proved a four-hour source expiry is reduced to the configured ten-minute maximum. Stale, unknown, partial, unavailable, revoked or policy-mismatched evidence cannot satisfy readiness.

Origin Transfer owns the coordinating journey. Receiving Transfer owns the local receiving request, assessments and Acceptance; receiving Capacity owns the reservation. The receiving Transfer invokes Capacity through its owner contract. No aggregate is cloned and no deployment shares business persistence. The user approved a typed Capacity reservation subject, `FEDERATED_RECEIVING_REQUEST`, because the existing local-Transfer-only subject could not safely represent a receiver-owned request. Existing `TRANSFER` behavior remains intact; subject type and owner-local subject ID participate in fingerprinting, uniqueness, lookup and release semantics. Capacity operation identity is distinct and stable.

Federated requests persist stable operation identity and fingerprint before network attempts. Exact duplicate requests recover the original receipt; changed content under the same identity conflicts. The local receiving request does not fabricate a Transfer, Referral, Journey or origin identity. Acceptance and reservation remain separate. Reservation commit followed by a lost response produces `OUTCOME_UNKNOWN`; status recovery with the same operation identity returns the original reservation without creating a duplicate. Unknown results block conflicting work according to Transfer invariants. Peer revocation is rechecked before retry. Source sequence/version, rather than remote wall-clock time alone, controls ordering.

## Privacy, authorization and audit

Federation and notification contracts carry opaque coordination and facility references, bounded operational facts, source/version information and correlation metadata only. They contain no PatientReference, IPS, diagnosis, clinical summary, name, attachment, token or credential. Owner APIs independently authorize resource reads. Peer operations independently authorize the named operation and scope. Trust changes, inbound/outbound operations, conflicts and reconciliation outcomes retain audit attribution without clinical payloads. Logs and metrics use bounded categories rather than patient or business-resource identifiers.

Local synthetic marker checks and structural payload tests cover notification messages, federation request/result contracts, durable receiving receipts, and the owner operation path. No production fixture contains patient data or credentials.

## Architecture, schema and dependency audit

- Architecture tests reject forbidden domain dependencies on HTTP/SSE types, cross-context persistence access, and Phase 12/Analytics/DHIS2 implementation boundaries.
- New persisted state is limited to the Phase 11 notification projection, deployment/peer identity and trust configuration, remote Directory/Capacity evidence projections, and owner-local federated operation/receipt state. Capacity retains typed reservation ownership. No shared federation database, cross-context or cross-deployment foreign key, or raw message archive was introduced.
- The migration inventory validates 73 migrations. Phase 11 additions are limited to migrations V63–V73 and existing context schemas; there is no Analytics/DHIS2 schema.
- `pom.xml` and `compose.yaml` have no changes from the Volume IV design baseline. No broker, Redis, DHIS2 client, provider SDK or new production dependency was added.
- No Phase 12 Analytics package, indicator implementation, DHIS2 adapter, test mock or ticket was created.

## Verification

Required command: `.\mvnw.cmd -B -ntp clean verify`
Result: **BUILD SUCCESS** on OpenJDK Temurin 25.0.4.1.

| Suite | Test count | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| Surefire | 386 | 0 | 0 | 0 |
| Failsafe | 316 | 0 | 0 | 0 |

Flyway validated 73 migrations. Formatting passed for 739 Java files. The local integration run exercised disposable PostgreSQL/PostGIS and Keycloak containers, in-process or controlled local peer/exchange doubles, migration upgrades, Phase 2 event/outbox behavior, Volume II Directory/Capacity/Referral/Matching/Transfer, Volume III FHIR/exchange/Transport, and Phase 11 notification and federation flows. No public or externally hosted healthcare/federation system was used. Compose smoke was not run because runtime Compose wiring did not change; the local container-backed integration suites ran instead.

## Independent code review

Independent standards and specification reviews found no unresolved BLOCKER or MAJOR. A MAJOR freshness-policy gap was corrected: federated Capacity evidence is now capped by the source policy maximum age and covered by a PostgreSQL-backed regression. Earlier review findings around source sequencing, response bounds and owner-result retention were also corrected and included in the clean verification. Remaining review notes are MINOR implementation-maintenance observations only; none changes a frozen ownership, privacy, trust or recovery guarantee. No finding requires Phase 12 work.

## Safety and phase gate

- No real/public federation peer, public healthcare endpoint, public FHIR server or DHIS2 system was contacted.
- No external mutation, account/OAuth registration, deployment, remote Git write or publication occurred.
- Only synthetic data, local test infrastructure and deterministic test peers were used.
- Phase 12 and Volume V remain unimplemented and unauthorized.

**Acceptance gate:** PASS. Phase 11 may be locally frozen. Phase 12 must not start without its separate human authorization.
