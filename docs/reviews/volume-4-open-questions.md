# Volume IV Design Question Disposition Register

**Design disposition:** no mandatory pre-implementation design question remains unanswered. The choices below are explicit safe defaults; deployment choices are configuration, not blockers to the architecture or specifications. Implementation may still uncover bounded technical details, but it must not reopen these ownership/safety decisions without a separately authorized design change.

The questions below are a resolved design register, not outstanding blockers. “Must resolve before implementation” is intentionally empty; dispositions document the choices future implementation must preserve.

## Must resolve before implementation

None. The questions below are answered design decisions and are retained as a traceable disposition register.

## Resolved design questions

| ID | Question | Disposition |
|---|---|---|
| Q1 | Initial client realtime mechanism? | Optional SSE for server-to-client hints; authorized conditional polling/full refresh fallback. No WebSocket absent bidirectional need. |
| Q2 | Truth if realtime delivery fails? | Owner state in PostgreSQL; hints are non-authoritative. |
| Q3 | Reconnect and replay? | Scope-bound monotonic cursor with bounded retention; cursor expiry or projection reset invalidates epoch and requires full authorized refresh. |
| Q4 | Long-lived subscription authorization? | Check at subscribe/reconnect and owner query; bound stream lifetime/recheck interval and close after revocation within that bound. |
| Q5 | Safe backpressure? | Per-client queue/replay/byte bounds; coalesce safe resource hints then refresh-required/disconnect; never block owner transaction/worker. |
| Q6 | Is current outbox enough? | Sufficient for durable internal events/recovery, not public stream replay or external federation. Use a bounded notification projection. |
| Q7 | Broker reconsideration threshold? | Only measured failure to meet documented throughput/latency/backlog recovery SLO after outbox tuning, or proven durable fan-out need; broker remains non-authoritative. |
| Q8 | Deployment identity? | Stable unique deployment-instance identity, separate from Organization; restored clone must not share an active identity. |
| Q9 | Federation trust? | Administrator-controlled Phase 9 peer profile scoped by operation, context, direction, org/facility, protocol/version and trust/config revision. |
| Q10 | Remote reference? | `(deploymentId, context, resourceId)` plus source version/provenance where available; UUID alone is insufficient. |
| Q11 | Federated Referral owner? | Originating deployment owns immutable Referral and PatientReference. |
| Q12 | Receiving assessment/Acceptance owner? | Receiving deployment's Transfer owner; it stores its local request/assessments/decision, not a clone of the full origin journey. |
| Q13 | Remote Capacity reservation? | Receiving Transfer invokes its local Capacity owner; Capacity commits locally; origin stores a namespaced outcome/reference. No shared transaction or 2PC. |
| Q14 | Remote acceptance/reservation response lost? | Origin records OUTCOME_UNKNOWN, queries/retries the same stable operation ID, and blocks conflicting attempts until resolved. |
| Q15 | Forbidden federation payload? | PatientReference, IPS/clinical content, names, attachments, credentials, raw owner payloads and unbounded provider data. |
| Q16 | Stale remote Capacity? | STALE/UNKNOWN with source, observed/received times, policy version and validUntil; never zero/READY. |
| Q17 | What continues during partition? | Local-authority facts continue; remote-dependent actions remain pending/unknown; cached remote views are freshness-labeled. |
| Q18 | Low-connectivity recovery? | Retry named operations with stable IDs and bounded backoff; reconcile status; use refresh/full query for UI; no offline multi-master editing. |
| Q19 | Analytics context? | Yes. Phase 12 formally introduces Analytics for derived aggregate facts only. |
| Q20 | How source facts arrive? | Each owner provides a bounded reporting snapshot computed inside its own persistence boundary. |
| Q21 | Prevent cross-context SQL? | Analytics imports only owner application reporting contracts; architecture tests prohibit owner repository/table access. |
| Q22 | Reproducibility? | Immutable indicator version, period/timezone/UTC bounds, exact bounded source measure inputs/references, cutoffs, completeness, mapping version and revision. |
| Q23 | Formula changes? | New immutable IndicatorDefinition version; historical definitions/snapshots remain unchanged. |
| Q24 | Missing versus zero? | Explicit completeness states; missing/partial/stale is not zero. Numeric zero only from complete sources and approved semantics. |
| Q25 | Late data/correction? | New linked revision; explicit finalization/correction with cause/audit; never rewrite exported history silently. |
| Q26 | Organization attribution? | Indicator version states source attribution: origin, destination, or named distinct dimensions; mapping is versioned. |
| Q27 | Prevent federated double-count? | Optional Phase 12 aggregate-to-aggregate exchange uses a named Phase 9 peer Analytics operation, authenticated source deployment, stable source snapshot ID/content fingerprint, explicit attribution, exact approved semantic compatibility, completeness and source provenance; incompatible versions do not combine. |
| Q28 | Small-cell privacy? | Release-set policy evaluates complementary cells, totals, dimensions, overlapping periods, revisions, prior AyuVahak releases and known external baselines; suppress/coarsen/withhold related outputs; if an external baseline is unknown, require conservative coarsening/review or block; no universal threshold. |
| Q29 | DHIS2 aggregate target? | Configured aggregate DataValueSet profile, researched against current official Core 2.43 docs; version capability is partner configuration, not a hard-coded universal release. |
| Q30 | Mapping versioning? | Effective-dated versioned mappings for target partner, dataElement, orgUnit, period, COC/AOC/dataset as applicable. |
| Q31 | Create target metadata? | No. Metadata is preconfigured and approved by deployment; automatic creation is out of scope. |
| Q32 | Export idempotency? | Stable local operation per partner + immutable snapshot revision + indicator/mapping version + canonical target tuple; remote exact replacement only when approved safe. |
| Q33 | Correction representation? | New local aggregate revision and linked export operation replacing only the exact approved tuple; explicit delete is outside default export. |
| Q34 | Partial import? | Initial profile sends exactly one DataValue per DataValueSet request because documented aggregate summary counts do not guarantee per-value attribution for multi-value imports. Each tuple has a separate operation; `imported=1`/`updated=1` acknowledges that tuple, while `ignored=1` remains unresolved pending authorized read-back or operator reconciliation. A release across operations may be partial. |
| Q35 | Lost response? | OUTCOME_UNKNOWN unless an approved read-back/reconciliation proves target state; never blindly retry/overwrite. |
| Q36 | Local DHIS2 acceptance? | Synthetic local mock is mandatory; optional disposable local DHIS2 container only if justified. Public/demo systems are prohibited as acceptance dependencies. |
| Q37 | Who owns official indicator meaning? | Analytics owns the AyuVahak calculation/version; the authorized public-health authority owns official interpretation and target metadata. Store its approved identifier/version as provenance when applicable. |
| Q38 | May peer aggregates be combined? | Yes, only through a named authorized Analytics peer contract with exact semantic compatibility, release-set privacy review, immutable provenance and duplicate/fingerprint conflict handling; no raw histories. |
| Q39 | Can concurrent releases bypass complementary suppression? | No. DHIS2 and peer outputs share one Analytics reservation gate. Every output that can be jointly recombined across target/channel/period/revision/dimensions shares a versioned policy domain; default to the broader policy/release scope, and use narrower domains only with proven non-overlap. A local transaction serializes all affected keys, evaluates current plus in-flight releases and reserves before network I/O; unknown results remain potentially disclosed. Incomplete mapping fails closed. PostgreSQL race acceptance is required. |
| Q40 | Can a disclosure policy revision reset history or create a new lock namespace? | No. Disclosure lineage IDs remain stable across policy versions. Mapping activation locks the old/new lineage union and carries old/in-flight reservations forward; merges preserve all lineage associations, and splits retain common historical lineage while outputs can still be recombined. Ambiguous continuity uses a broader lineage or fails closed. Acceptance races a release across policy rollover. |
| Q41 | Can a newer DHIS2 correction overtake an older in-flight/unknown value for the same tuple? | No. One durable active-operation claim exists per complete target tuple. New revisions wait while the prior operation may still apply; `OUTCOME_UNKNOWN` holds the barrier across restart until authorized reconciliation. The initial profile assumes no remote conditional-write guarantee. Acceptance delays an older local mock operation and proves the correction remains unsent until the predecessor resolves. |
| Q42 | Can DHIS2 asynchronous import be used by the initial profile? | No. Initial export is synchronous only; task acceptance is not terminal value delivery and cannot release the tuple barrier. Any future async profile requires a separately approved spec/profile, durable task ID, and terminal task-result/reconciliation semantics. |

## Deployment configuration

- Federation peer identity, trust state, allowed operation/scope, protocol profile, credentials by Phase 9 reference, endpoint and revocation policy.
- Notification connection lifetime, reauthorization interval, replay window/cursor and per-client queue/byte/connection limits.
- Remote Capacity freshness TTL and owner policy version.
- Indicator catalogue, attribution, reporting timezone/calendar, reporting frequency, source freshness/completeness, correction window and finalization policy.
- DHIS2 partner API capability/version, authentication profile, metadata UIDs, mapping version, target zero behavior, write ownership, read-back support and retry ceilings.
- Release-set disclosure controls, thresholds/coarsening and release-history retention for indicators that require small-number protection; known target-side baseline or approved conservative fallback where relevant.

## Future volume

- Generic multi-master synchronization, shared global resource identity, arbitrary offline aggregate editing, and cross-deployment authoritative data lake.
- Patient-level DHIS2 Tracker/Event export, national public-health datasets and public analytics products.
- Broad notification channels, generic BI, predictive analytics, and policy feedback into clinical decisions.
- Broker adoption unless the evidence-based threshold in ADR 0026 is met and separately approved.
- Any capability beyond Phases 11 and 12 remains outside Volume IV and requires a separate design authorization.
