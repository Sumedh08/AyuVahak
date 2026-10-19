# Production retention register

Production profiles require a deployment-owned register under
`ayuvahak.production.retention.register`. There is deliberately no built-in retention
duration or example schedule that could be mistaken for a legal or operational default.
Supply the entries from an administrator-controlled configuration source alongside the
schedule identifier, review date, and accountable authority. Missing, incomplete,
misattributed, or contradictory entries prevent the production profile from starting.

The register is a policy declaration. It does not run a central purge job. Each listed owner
applies its own expiry, minimization, hold, and verification behavior inside its persistence
boundary. A duration only makes a record eligible for owner review; it never bypasses an active
hold, unresolved business/replay/reconciliation dependency, or audit/disclosure invariant.
Completed retention actions must be reapplied after restoring an older backup and before that
restored deployment serves traffic.

## Required entries

There must be exactly one entry for every category below. The `owner` must match the listed
owner. `retention-period` is a positive ISO-8601 duration selected by the accountable
deployment authority; it is not a universal product value.

| Register key | Owner |
|---|---|
| `PLATFORM_IDENTITY_GRANTS` | `PLATFORM` |
| `PLATFORM_AUDIT` | `PLATFORM` |
| `PLATFORM_IDEMPOTENCY` | `PLATFORM` |
| `PLATFORM_EVENT_OUTBOX` | `PLATFORM` |
| `PLATFORM_NOTIFICATION_PROJECTION` | `PLATFORM` |
| `DIRECTORY_PROJECTIONS` | `DIRECTORY` |
| `CAPACITY_EVIDENCE_AND_RESERVATIONS` | `CAPACITY` |
| `REFERRAL_CASES` | `REFERRAL` |
| `MATCHING_RECOMMENDATION_EVIDENCE` | `MATCHING` |
| `TRANSFER_HISTORY` | `TRANSFER` |
| `TRANSPORT_HISTORY` | `TRANSPORT` |
| `PHASE_9_EXCHANGE` | `EXCHANGE` |
| `PHASE_11_FEDERATION` | `FEDERATION` |
| `PHASE_12_ANALYTICS` | `ANALYTICS` |
| `LOGS_METRICS_AND_TRACES` | `OBSERVABILITY` |
| `BACKUPS_AND_RESTORE` | `BACKUP` |

Every entry supplies:

- `owner`, `purpose`, and `data-class`;
- `access-policy` and `lifecycle-trigger`;
- `retention-period` and `governance-basis`;
- `hold-review-authority` and an `expiry-action`;
- `evidence-dependencies`, `backup-propagation`, and `verification-method`.

Supported expiry actions are `PURGE`, `MINIMIZE_OR_PSEUDONYMIZE`, `EXPIRE_PROJECTION`,
`RESTRICTED_TOMBSTONE`, and `OWNER_REVIEW`. Operators must choose an action supported by that
owner's implementation and approved schedule. `OWNER_REVIEW` means a human owner decision is
still required; it is not an automatic deletion permission.

The implementation requires each schedule to preserve three invariants: disposal is blocked
while a hold is active, disposal is blocked while the owner's work/replay/reconciliation state
is unresolved, and completed actions are reapplied before a restored database is made ready.
The schedule also names the review authority and verification method used by the deployment.

No schedule entry may contain person, patient, case, resource, or credential identifiers. Do
not put secret values, clinical information, or raw audit/event payloads in this register.

## Ownership and evidence

The platform may validate that the register is complete, but it does not decide that a
Referral, Capacity, Transfer, Transport, Analytics, or other owner record is disposable. The
owning context must evaluate its own lifecycle and unresolved dependencies, apply its local
hold, and emit bounded evidence without resource identifiers or payloads. Legal conclusions,
retention duration approval, and hold release authority remain deployment governance
responsibilities. The register is not a retention-law compliance claim.
