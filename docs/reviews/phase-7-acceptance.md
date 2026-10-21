# Phase 7 Transfer Coordination — Acceptance Record

**Verification result:** PASS. **Independent final review:** PASS. **Phase 7 result:** FROZEN.

## Scope and test environment

The counts below are the historical Phase 7 freeze-time run (139 Surefire / 222 Failsafe). The later cross-volume closeout run is recorded separately in [Volume II acceptance](volume-2-acceptance.md); it reran the full suite after closeout remediation and must not be conflated with the original Phase 7 gate evidence.

Phase 7 implements coordination state for a Transfer Journey and its destination attempts, receiving assessments and acceptance, Capacity-owned reservation coordination, movement/progress facts, Arrival, Handover, Completion, cancellation/release reconciliation, rerouting, and participant-scoped views. It does not implement Transport dispatch, patient-record storage, clinical judgment, or external healthcare connectors.

Verification used Java 25.0.4.1+1, the repository Maven Wrapper, synthetic fixtures, and disposable local Testcontainers. PostgreSQL 18.6 with PostGIS 3.6.4 applied all 37 Flyway migrations. No public healthcare, identity, routing, or external service was a test target.

## Integrated verification

| Requirement | Evidence | Result |
|---|---|---|
| Full clean build and verification | `.\mvnw.cmd -B -ntp clean verify` using Java 25.0.4.1+1 | PASS; Surefire 139 tests and Failsafe 222 tests, with 0 failures, errors, or skips; packaging and Spotless completed. The run completed in approximately 15 minutes. |
| Phase 7 command/API behavior | `TransferJourneyCommandsApiIT` in the full suite | PASS; 63 tests, including requests, distinct assessments, acceptance, reservations, lifecycle facts, rerouting, participant views, privacy, scope-denial audit, and failure/recovery paths. |
| Architecture enforcement | `Phase1ArchitectureTest` in the full suite | PASS; 11 production architecture rules. |
| PostgreSQL transaction and race semantics | Testcontainers-backed integration suite | PASS; includes last-unit reservation contention, terminal transition races, restart/reconciliation, duplicate and delayed events, and transaction boundaries. |
| Migrations and database ownership | Fresh Testcontainers database; Flyway history and schema review | PASS; 37 migrations applied, with owner schemas only and no placeholder future business schemas. |
| Compose local deployment smoke | `docker compose --env-file .env.example -p ayuvahak-p7-reaudit config --quiet`; isolated `up --build --detach`; loopback probes; local SQL inspection; `down --volumes --remove-orphans` | PASS for platform smoke: health, liveness and readiness were HTTP 200; `/api/v1/system-info`, Prometheus and Keycloak discovery returned successfully; PostgreSQL 18.6, PostGIS 3.6.4, Flyway v37, and expected owner schemas were observed. A prior isolated smoke verified a synthetic `transfer-agent` client-credentials request received the expected 403 because the fresh disposable database has no grant; authorized business behavior is verified in Testcontainers API suites. The isolated project, network and volume were removed. |
| Privacy and scope | Production source/migration search and dependency-tree review | PASS; Transfer does not persist PatientReference or clinical payload; no prohibited healthcare connector, broker, search/graph database, ORM, or AI/LLM dependency was found. |
| Formatting and patch hygiene | `./mvnw -o -B -ntp spotless:check`; `git diff --check` | PASS. |

The full clean command returned `BUILD SUCCESS`. Testcontainers shutdown produced connection-refused/pool-validation warnings from scheduled workers after individual disposable databases stopped. One Surefire fork exceeded its 30-second shutdown allowance and Maven forcibly terminated that fork after its tests completed; the reported test results passed with zero failures or errors and Maven returned success. These are test-process cleanup caveats, not a healthcare or application-data failure.

## Reliability and privacy evidence

- Acceptance and reservation remain separate outcomes. Transfer asks Capacity to reserve through its owner operation; it does not directly mutate Capacity persistence.
- Lost or unknown reservation responses are reconciled through the stable operation identity and Capacity-owned outcome read. Expiry, release, and at-risk handling retain explicit pending/confirmed outcomes.
- Transfer records chronology and coordination references. It does not store PatientReference, demographics, clinical summaries, notes, attachments, or longitudinal clinical records.
- Participant-scoped reads constrain which attempts and outcomes are visible. Integration tests cover access scope, revocation, paging, audit outcomes, and privacy.
- Event behavior is at-least-once and deduplicated at the consumer boundary. `referral.cancelled.v1` is handled by the Phase 7 consumer without replaying historical waiting events as new business actions.
- Delayed Capacity reservation recovery uses the Capacity-owned `capacity.reservation-recovered.v1` fact. It records the owner outcome as reserved without regressing Transfer progress such as Arrival; an in-exception recovery still requires receiving-side reconfirmation before progress resumes.
- Transfer amendment-status reads require an exact origin organization and, when present, matching origin facility. A mismatched scope is denied and durably audited; if the audit write fails, the request fails closed with `AUDIT_UNAVAILABLE`.
- All examples and fixtures use synthetic identifiers/data. No real credentials or patient data were used.

## Review findings and remediation

- **MAJOR, resolved:** independent review found that Arrival from `COORDINATION_EXCEPTION` was rejected despite the spec's reality-ahead-of-digital rule. A regression first reproduced the `409`; Transfer now records Arrival with `ARRIVAL_DURING_COORDINATION_EXCEPTION`. The regression passes against disposable PostgreSQL/PostGIS.
- **MAJOR privacy finding, resolved:** independent review found a Capacity lifecycle warning included `transferId`, contrary to the observability rule. A captured-output integration regression reproduced the leak; the warning now records only the failure type. The regression passes against disposable PostgreSQL/PostGIS.
- **MAJOR recovery finding, resolved:** final review found that delayed Capacity recovery could leave an already-arrived Journey in `COORDINATION_EXCEPTION` because Transfer ignored the recovery event after Arrival. Transfer now records the Capacity-owned outcome as reserved while preserving later Journey progress. A red/green PostgreSQL integration regression verifies it.
- **MAJOR scope/audit finding, resolved:** final standards review found amendment-status could accept a facility-origin request using only a matching organization context and that its scope-mismatch denial lacked a durable audit record. Transfer now requires exact optional facility scope, records a minimized denial audit, and fails closed if that audit cannot be persisted. Integration regressions cover organization-only mismatch, successful denial audit, and injected audit-store failure.
- **MINOR, clarified:** P7-07 had described receiving-side PatientReference access as available, but no recipient query exists. The canonical Phase 7 spec and ticket now state that Transfer views expose coordination references only and the optional Referral `GetTransferBasis` disclosure is not activated by the Phase 7 participant-view API. If a deployment needs that sensitive read, Transfer must verify selected-recipient case participation and Referral must independently authorize and audit disclosure. No patient-reference endpoint or Transfer persistence was added.
- **MINOR maintainability observations:** repeated scoped execution-context parsing across Transfer controllers and the large shared `TransferJourneyCommandsApiIT` class remain acceptable heuristics; neither violates a documented boundary or blocks the approved workflow.

## Review gate

The first Phase 7 specification review found one BLOCKER because this acceptance artifact and P7-08 completion evidence were missing. The evidence is now present. Independent review identified and resolved the MAJOR findings above. Final specification and standards re-reviews found no remaining findings: recovery preserves Arrival, amendment checks enforce exact origin scope, and scope denials are durably audited or fail closed. The audit-failure regression injects a database rejection and verifies `AUDIT_UNAVAILABLE`. Conditional patient-context scope remains accurately stated. Two maintainability heuristics remain documented and do not block the phase.

With explicit user authorization, two test-only integration fixtures were corrected: the Phase 1 missing-database test asserts the actual fail-closed startup failure, and the frozen Phase 2 outbox test supplies its deterministic handler registry so the historical “future consumer” scenario remains isolated after Phase 7 registers a real handler. Product behavior and the frozen Phase 2 design are unchanged.

## Safety and freeze status

- No remote Git operation, public healthcare test target, external publication, external mutation, real credential, or patient data was used.
- No Volume III implementation was created.
- Phase 7 acceptance and independent re-review **pass**. Final review-fix commit: `1ed104a6364ccc455876b839c99846cae1a34b24`.

See the local [P7-08 integrated acceptance ticket](../../.scratch/phase-7-transfer-coordination/issues/08-integrated-acceptance.md) and [Phase 7 specification](../specs/phase-7-transfer-coordination-spec.md) for the approved gate and complete test contract.
