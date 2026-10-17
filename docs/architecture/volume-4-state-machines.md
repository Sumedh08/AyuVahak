# Volume IV State Machines

These state tables describe future application behavior only. They do not imply implementation exists. Owner transactions persist transitions; external messages and notifications are not the source of truth.

## Client notification cursor

| State/input | Authority/check | Result |
|---|---|---|
| Subscribe with no cursor | Authenticate and authorize requested bounded scope | Start stream from current bounded sequence; emit refresh marker where a gap cannot be ruled out |
| Subscribe with valid cursor in retained window and current cursor epoch | Reauthorize scope and bind cursor to that scope | Replay only retained hints after cursor, then live hints |
| Projection reset / cursor epoch changed | Reject all old cursors | Return `refresh-required`; begin a fresh bounded cursor after full authorized refresh |
| Cursor too old, malformed or another scope | Reject cross-scope cursor; do not scan unbounded history | Emit `refresh-required` or return bounded reset response; client performs full authorized query |
| Grant revoked / connection lifetime reached | Recheck current authorization | Close stream; reconnect requires fresh authorization |
| Slow-client queue full | Coalesce same-resource hints; retain queue bound | Send `refresh-required` if possible, then disconnect; no worker or business-write blocking |

## Federated operation

| State | Allowed transition | Authority / side effect | Retry and recovery |
|---|---|---|---|
| `PENDING` | `SENT`, `REJECTED`, `CANCELLED` | Origin owner records operation and validates peer/config/scope | No send until active peer and local authorization pass |
| `SENT` | `RESOLVED`, `OUTCOME_UNKNOWN`, `RECONCILIATION_REQUIRED` | Remote owner commits its local result and returns a named operation outcome | Network retry uses same peer-scoped operation ID and fingerprint |
| `OUTCOME_UNKNOWN` | `RESOLVED`, `RECONCILIATION_REQUIRED` | Only remote status/read-back or authorized operator evidence resolves | Do not create conflicting attempt/reservation or infer rejection |
| `RECONCILIATION_REQUIRED` | `RESOLVED`, `CANCELLED` | Owner verifies source sequence/fingerprint and records reason | Bounded retries; manual resolution is audited |
| `RESOLVED`, `REJECTED`, `CANCELLED` | terminal | Historical operation remains immutable; resolved outcome is separately classified (e.g. accepted, accepted-but-unreserved, reservation conflict) | Duplicate exact request returns recorded outcome; changed fingerprint conflicts |

The receiving owner has a peer/message receipt keyed by peer deployment, operation ID and fingerprint. Exact duplicate recovers the stored result. Reuse of an operation ID with changed content is a security conflict. Receiving Transfer decision and Capacity reservation remain distinct owner effects with separate operation identities and transaction/outbox boundaries; cross-deployment 2PC is prohibited. `RESOLVED` describes completion of the named remote operation, not that a Transfer is reserved or READY; business outcome is explicit in the response.

## Remote evidence freshness

| State | Condition | Use |
|---|---|---|
| `CURRENT` | Authenticated source, compatible policy/version and `now < validUntil` | May be considered by Matching/Transfer under existing owner decision rules |
| `STALE` | Freshness deadline passed, source version conflict, or source deactivated | Display with provenance only; cannot satisfy readiness/action precondition |
| `UNKNOWN` | Peer unavailable, no evidence, revoked trust, incomplete source response, invalid mapping | Must not be treated as zero, available, or eligible |

Recovery requires a new owner-authorized source observation; wall clock alone cannot promote stale data.

## Analytics aggregate snapshot

| State | Transition | Required evidence |
|---|---|---|
| `COLLECTING` | `COMPLETE`, `PARTIAL`, `MISSING_SOURCE`, `STALE_SOURCE`, `NOT_APPLICABLE` | Versioned owner snapshots and source cutoffs for the requested period/scope |
| `COMPLETE` | `PROVISIONAL`, `FINALIZED` | All required inputs complete; numeric zero allowed only from complete evidence |
| `PARTIAL` / `MISSING_SOURCE` / `STALE_SOURCE` | `COMPLETE` or remain non-exportable | Corrected owner snapshot/cutoff; never auto-convert to zero |
| `PROVISIONAL` | New immutable revision; `FINALIZED` after correction window/authorized finalization | Late fact, completeness change, or correction reason; old revision preserved |
| `FINALIZED` | Explicit corrected revision only | Authorized, audited correction with cause and link to prior revision |

Snapshot identity includes indicator key/version, exact period, organization/unit, approved dimensions, input cutoffs, revision and computation time. A changed formula is a new indicator version, not a mutated historical rule.

## DHIS2 export operation

| State | Allowed transition | Meaning |
|---|---|---|
| `READY` | `SENDING`, `BLOCKED_POLICY`, `FAILED_PERMANENT` | Snapshot is complete, privacy-cleared and mapped to a configured target |
| `SENDING` | `ACKNOWLEDGED`, `PARTIAL`, `RETRY_PENDING`, `OUTCOME_UNKNOWN`, `FAILED_PERMANENT` | A bounded attempt is in progress; a 2xx is parsed at value-level |
| `PARTIAL` | `RECONCILIATION_REQUIRED`, `READY` for explicitly failed values only | Some target tuples succeeded while others did not; never mark whole batch successful |
| `RETRY_PENDING` | `SENDING`, `BLOCKED_POLICY`, `FAILED_PERMANENT` | Only classified safe transient failure, bounded by attempts and elapsed time |
| `OUTCOME_UNKNOWN` | `ACKNOWLEDGED`, `PARTIAL`, `RECONCILIATION_REQUIRED`, `FAILED_PERMANENT` | Response loss may have applied values; no blind duplicate overwrite |
| `RECONCILIATION_REQUIRED` | `ACKNOWLEDGED`, `READY` for explicit correction, `FAILED_PERMANENT` | Authorized read-back or operator resolves exact target tuple |
| `ACKNOWLEDGED`, `FAILED_PERMANENT`, `BLOCKED_POLICY` | terminal for this revision/operation | Later correction uses a new immutable aggregate revision and operation |

No state means that DHIS2 validates the public-health meaning of an indicator. Explicit `DELETE` is outside the normal export path and requires a separate authorized correction design.
