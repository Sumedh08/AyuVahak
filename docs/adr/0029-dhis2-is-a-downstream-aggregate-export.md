---
status: accepted
date: 2026-10-17
---

# ADR 0029: DHIS2 is a downstream aggregate export

## Context

DHIS2 supports several data models and import behaviors, but successful HTTP delivery does not establish indicator correctness or complete acceptance. Official Core 2.43 documentation describes aggregate DataValueSet imports and import summaries while not promising client idempotency, all-or-nothing imports, durable status lookup after a lost response, or omission-as-delete. Target metadata and authentication vary by deployment.

## Decision

Phase 12 exports only approved aggregate DataValueSet values from immutable Analytics snapshot revisions. The initial profile uses synchronous imports and sends one DataValue per request because documented import summaries do not guarantee unambiguous per-value outcomes for a multi-value request. Async-import task IDs are unsupported and cannot count as completed delivery; a future async profile must persist task identity and retain the tuple barrier until a terminal result. `imported=1` or `updated=1` acknowledges delivery of that tuple only; `ignored=1` is unresolved without authorized read-back or operator reconciliation. It does not use Tracker/Event for patient-level export. DHIS2 remains downstream and is never an operational or identity authority. Preconfigured, versioned deployment mappings bind indicator/version and organization/dimension/period to target dataElement/orgUnit/category/attribute combinations. AyuVahak does not create target metadata by default.

Use Phase 9 partner trust and transport; select only a target-approved supported authentication profile. Store a stable local operation identity for each target tuple and the documented import summary. Permit at most one unresolved outbound operation per complete target tuple; a newer revision waits until the previous one has a documented terminal result or authorized reconciliation. An `OUTCOME_UNKNOWN` retains the tuple barrier across restart because a delayed older request might still apply; no target conditional-write guarantee is assumed. Treat an export set as potentially partial across its separate tuple operations. A response loss yields OUTCOME_UNKNOWN unless an authorized read-back or explicitly safe same-tuple replacement resolves it. Omission is not zero or deletion. A correction is a new audited revision; explicit deletion is outside normal export. Emit zero only from complete source evidence with known target zero semantics. Release-set privacy analysis also considers prior outputs and differencing across dimensions, totals, periods and revisions.

## Consequences

- Local synthetic mock acceptance is sufficient; public/demo DHIS2 is never required.
- Acknowledgment means only that the target reported a transport/import outcome, not that the indicator is valid.
- Mapping, indicator and partner configuration changes are versioned and do not rewrite historical snapshots.
- Small-count disclosure policy is evaluated before export; missing required policy blocks the sensitive cell.

## Alternatives considered

- DHIS2 Tracker/Event export was rejected because no patient-level use is authorized and aggregate reporting is sufficient for this phase.
- Automatic metadata creation was rejected because target metadata is deployment-governed and may affect shared public-health configuration.
- Blind retries after timeout were rejected because remote idempotency/status lookup is not documented as universal.
- Treating omitted DataValues as deletion/zero was rejected because the official import documentation does not establish either semantic.

## References

- [Volume IV architecture](../architecture/volume-4-resilient-operations-federation-public-health.md)
- [Phase 12 specification](../specs/phase-12-public-health-analytics-dhis2-spec.md)
- [Official technical research](../research/volume-4-technical-research.md)
