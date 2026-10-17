# Volume IV Architecture and Specification Review

**Review date:** 2026-10-17
**Scope:** Phase 11 and Phase 12 design only.
**Result:** PASS after remediation; no unresolved BLOCKER or MAJOR findings.
**Implementation:** Not started and not authorized.

## Review basis

The review checked the root `AGENTS.md`, product/architecture constitution, frozen Volumes I–III, Phase 9 exchange boundary, Volume IV architecture, contracts, state machines, ownership/privacy/threat documents, Phase 11/12 specifications, ADRs 0026–0029, open-question dispositions, and official research recorded in [Volume IV technical research](../research/volume-4-technical-research.md). Two independent reviewers performed separate standards/semantic and specification/architecture passes against the local documents. No live healthcare, DHIS2, federation, or external service was contacted.

## Grill-with-docs conclusions

| Challenged assumption | Decision |
|---|---|
| Real-time requires Kafka | Rejected. The existing transactional outbox remains the durable internal event substrate. A broker may be reconsidered only after measured SLO failure or a demonstrated durable-fan-out need that the outbox cannot safely meet. |
| Resilience requires microservices, Redis, or a service mesh | Rejected. Owner contexts remain within the existing deployment architecture; database state and outbox recovery are authoritative. |
| Federation requires a shared database or unrestricted multi-master replication | Rejected. Independently administered deployments exchange named, scoped operations through Phase 9. Each fact has a home owner and remote uncertainty remains explicit. |
| WebSocket state is the source of truth | Rejected. Optional SSE sends bounded hints; authorized queries and conditional polling read current owner state. |
| Every internal event should leave the deployment | Rejected. Only named peer operations with explicit authority, purpose, minimization and reconciliation may cross the boundary. |
| Offline operation should replicate all aggregates | Rejected. Locally authoritative facts may proceed; remote-authority actions remain pending/unknown; cached remote evidence carries freshness and becomes stale/unknown. |
| Analytics can join every private schema | Rejected. Each source owner returns bounded reporting snapshots from its own persistence boundary. |
| DHIS2 Tracker/Event should be used for patient-level data | Rejected. Phase 12 exports approved aggregate DataValueSets only; patient and clinical data are prohibited. |
| A successful DHIS2 request proves an indicator is correct | Rejected. Delivery acknowledgment is distinct from indicator meaning, completeness and public-health interpretation. |
| Missing values mean zero; formulas can be silently changed | Rejected. Completeness is explicit; indicator definitions and snapshots are versioned and corrections create linked revisions. |
| DHIS2 metadata should be created automatically | Rejected. Target metadata and mappings are administrator-approved and preconfigured. |
| Public DHIS2 is a useful acceptance target | Rejected. All future acceptance uses synthetic fixtures and a local deterministic mock, with public networking disabled where feasible. |
| Individual cell suppression is sufficient under concurrency | Rejected. A shared disclosure reservation gate serializes related releases, retains unknown outcomes, and uses immutable disclosure lineage across policy versions. |
| Newer corrections may overtake a delayed older DHIS2 write | Rejected. A durable single-flight barrier per target tuple blocks newer revisions while an older operation may still apply. |
| Async import acceptance means the value is applied | Rejected for the initial profile. It is synchronous and one DataValue per request; async task IDs cannot clear the barrier. |

## Independent findings and resolutions

| Severity | Finding | Resolution |
|---|---|---|
| MAJOR | Aggregate import summaries do not establish an unambiguous per-value result for multi-value requests. | Initial profile sends exactly one DataValue per synchronous DataValueSet request. `ignored=1` requires authorized read-back or operator reconciliation. Partial releases are represented as separate operation outcomes. |
| MAJOR | Per-cell small-count suppression could be defeated by totals, dimensions, overlapping periods, revisions or prior outputs. | Release-set disclosure review includes related outputs and known external baseline; absent/uncertain policy fails closed or requires approved coarsening/review. |
| MAJOR | Concurrent individually safe DHIS2 and peer releases could jointly reveal a withheld cell. | Both paths use a shared local Analytics reservation gate; reservations are committed before send and count as potentially disclosed. PostgreSQL concurrency acceptance is required. |
| MAJOR | Policy-version changes could reset the lock/history namespace. | Immutable disclosure-lineage IDs persist across policy versions. Policy/mapping activation locks old/new lineages and carries history/reservations; uncertain continuity uses a broader lineage or fails closed. |
| MAJOR | Distinct revisions for one DHIS2 tuple could be applied out of order. | A durable single-flight barrier permits at most one unresolved operation per full target tuple. Unknown or in-flight predecessors block newer revisions across restart. Automated write requires exclusive AyuVahak ownership or a documented target conditional/version guard. |
| MAJOR | DHIS2 may accept asynchronous imports and return task identifiers. | Initial profile is synchronous-only. Async task IDs are unsupported and cannot clear the tuple barrier; a future profile needs separate approval plus durable task tracking and terminal reconciliation. |
| MINOR | Optional aggregate peer federation was not consistently reflected in the first draft. | It is explicitly in Phase 12 scope with exact semantic compatibility, privacy review, provenance and deduplication; raw owner facts remain excluded. |
| DOCUMENTATION | Analytics calculation authority and official public-health interpretation were conflated. | Analytics owns its formula/version; the authorized public-health authority owns official meaning and target metadata. |
| DOCUMENTATION | Phase status and ADR-index wording could imply the whole design was frozen before independent review. | The design review passed before status was frozen; the ADR index clarifies accepted ADR status is a decision record, not a substitute for the Volume IV freeze gate. |
| DOCUMENTATION | Correction could be interpreted as reusing an old export operation. | Prior operation remains unchanged; correction uses a new immutable snapshot revision, operation identity and disclosure reservation. |
| DOCUMENTATION | DHIS2 result detail, write authority and async import semantics were underspecified in a few summaries. | Invalid/rejected outcomes are permanent only when the profile documents them; automated writes require exclusive ownership or a documented conditional/versioned guard; async imports are excluded initially. |

## Final review classification

- **BLOCKER:** 0 unresolved.
- **MAJOR:** 0 unresolved.
- **MINOR:** no unresolved issue prevents design freeze; all material privacy and authority risks have explicit deployment controls and acceptance cases.
- **DOCUMENTATION:** resolved as listed above.

## Local-only verification

- All relative Markdown links in changed/new design documents resolve.
- `git diff --check` passes.
- Tracked changes are documentation-only; no Java, tests, migrations, schemas, dependencies, Compose services or runtime adapters were added.
- All proposed Phase 11/12 acceptance uses synthetic local fixtures, local PostgreSQL and controlled mock peers. No public DHIS2 instance is required or permitted.
