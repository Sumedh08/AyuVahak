# Volume IV State Machines

These state tables define the Phase 11/12 behavior and remain the reference for their implementations. Owner transactions persist transitions; external messages and notifications are not the source of truth. Verification evidence is recorded in the phase acceptance records.

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

## Aggregate disclosure release decision

| State | Transition | Required evidence / effect |
|---|---|---|
| `REVIEW_PENDING` | `RESERVED`, `WITHHELD`, `REVIEW_REQUIRED` | Candidate value and related release set are evaluated under the effective privacy policy against current and retained prior disclosures |
| `RESERVED` | `DISCLOSED`, `POTENTIALLY_DISCLOSED`, `WITHDRAWN_NOT_SENT` | An atomic Analytics transaction serializes stable disclosure-lineage IDs resolved by the active policy mapping, evaluates acknowledged plus in-flight reservations, records the policy/history checkpoint and stable reservation; no database lock is held during network I/O |
| `DISCLOSED` | terminal for the disclosure event | Acknowledged value is included in subsequent differencing analysis |
| `POTENTIALLY_DISCLOSED` | `DISCLOSED`, `WITHDRAWN_NOT_SENT`, or remain | Outcome is unknown; treat as disclosed for all subsequent checks and retry only with the same reservation/operation ID |
| `WITHDRAWN_NOT_SENT` | terminal for the disclosure event | May be reached only with Phase 9 evidence that no request bytes could have left AyuVahak; reservation can then be excluded from disclosure history |
| `WITHHELD` | `REVIEW_PENDING` after policy/data change only | Suppression/coarsening/withholding decision is retained; absence is not a zero |
| `REVIEW_REQUIRED` | `RESERVED`, `WITHHELD` | Authorized reviewer or updated release history resolves uncertainty; no external send while unresolved |

DHIS2 export and federated Analytics publication use this same gate. A stable lineage ID covers every output that may be jointly recombined across destination, channel, period, revision or dimensional projection. The conservative default is the broader policy/release-scope lineage; narrower lineages require proven non-overlap. If complete mapping cannot be proven, use that broader lineage or fail closed. Concurrent workers lock all affected lineage IDs in deterministic order, re-evaluate after acquiring the lock, and atomically persist the reservation. A reservation counts as potentially disclosed for the next worker. The disclosure decision is made before each value leaves the deployment; prior releases and unresolved outcomes cannot be erased by changing policy.

Lineage IDs are immutable across policy/mapping versions. Activating a new assignment locks the union of old and proposed lineages and preserves all old/in-flight reservations. Merges retain prior lineage associations; splits retain the common historical lineage for descendants that remain recombinable. A lineage can be retired only after approved review establishes no possible combination with past/future releases. Otherwise use a broader lineage or fail closed. Policy update and mapping activation are atomic with respect to release reservations.

## DHIS2 export operation

The initial supported operation is synchronous, carries exactly one DataValue and must have a live `RESERVED` disclosure entry. Async-import options and job/task IDs are unsupported and cannot clear an operation or tuple barrier. A separately approved asynchronous profile would persist the remote task identity and keep the barrier until a documented terminal task result or authorized reconciliation. An export set spanning several tuples is a collection of independent operations; its `PARTIAL` result is derived from their distinct outcomes and is not inferred from an ambiguous multi-value import summary.

| State | Allowed transition | Meaning |
|---|---|---|
| `READY` | `SENDING`, `BLOCKED_POLICY`, `FAILED_PERMANENT` | Snapshot is complete, privacy-cleared and mapped to a configured target |
| `SENDING` | `ACKNOWLEDGED`, `RECONCILIATION_REQUIRED`, `RETRY_PENDING`, `OUTCOME_UNKNOWN`, `FAILED_PERMANENT` | A bounded attempt is in progress; documented summary counts are interpreted for the single tuple only |
| `RETRY_PENDING` | `SENDING`, `BLOCKED_POLICY`, `FAILED_PERMANENT` | Only classified safe transient failure, bounded by attempts and elapsed time |
| `OUTCOME_UNKNOWN` | `ACKNOWLEDGED`, `RECONCILIATION_REQUIRED`, `FAILED_PERMANENT` | Response loss may have applied the value; no blind duplicate overwrite |
| `RECONCILIATION_REQUIRED` | `ACKNOWLEDGED`, `FAILED_PERMANENT` | Authorized read-back/operator resolves this operation only; any correction is a new immutable revision, operation identity and disclosure reservation |
| `ACKNOWLEDGED`, `FAILED_PERMANENT`, `BLOCKED_POLICY` | terminal for this revision/operation | Later correction uses a new immutable aggregate revision and operation |

`imported=1` or `updated=1` is delivery acknowledgment for the one tuple. `ignored=1` moves to `RECONCILIATION_REQUIRED` unless an authorized read-back confirms the expected target value; aggregate counts do not explain the ignored reason. An export set is reported `PARTIAL` when one or more independent tuple operations are acknowledged and one or more are failed, blocked or unresolved. No state means that DHIS2 validates the public-health meaning of an indicator. Explicit `DELETE` is outside the normal export path and requires a separate authorized correction design.

### Per-tuple ordering barrier

Analytics maintains one durable active-operation claim for each configured target identity `(partner, dataElement, period, orgUnit, categoryOptionCombo, attributeOptionCombo)`. Automated writes require exclusive AyuVahak write ownership for that tuple or a documented conditional-write/version guarantee; otherwise they are blocked for an authorized operator-mediated path. A new revision cannot send while an older operation for that exact tuple is `SENDING`, `RETRY_PENDING`, `OUTCOME_UNKNOWN` or `RECONCILIATION_REQUIRED`. The claim is committed before network I/O and is not released by process death or lease expiry. Only a documented terminal outcome or authorized reconciliation clears it; if the target profile provides a proven conditional-write/version guarantee, a separately approved implementation may use that guarantee instead. The initial Phase 12 profile assumes no such remote guarantee. Thus a delayed older request cannot race a newer correction, and an unknown older outcome blocks the newer send until resolved.

## Federated aggregate publication and receipt

| State | Allowed transition | Meaning |
|---|---|---|
| `READY` | `SENDING`, `BLOCKED_POLICY` | Immutable eligible snapshot has a live `RESERVED` disclosure entry and a configured Analytics peer operation |
| `SENDING` | `ACKNOWLEDGED`, `OUTCOME_UNKNOWN`, `REJECTED` | Stable source snapshot ID, definition fingerprint and content fingerprint are sent through Phase 9 |
| `OUTCOME_UNKNOWN` | `ACKNOWLEDGED`, `RECONCILIATION_REQUIRED`, `REJECTED` | Resolve using the same peer operation ID; never create a replacement publication while outcome is unknown |
| Receiving `RECEIVED` | `COMBINABLE`, `INCOMPATIBLE`, `REJECTED` | Receiving Analytics verifies peer/scope, fingerprint, completeness, privacy decision, source provenance and explicit semantic mapping |
| `COMBINABLE` | terminal for this receipt | Aggregate may be combined once under the approved attribution; duplicate exact fingerprint returns the same receipt |
| `INCOMPATIBLE`, `REJECTED`, `BLOCKED_POLICY` | terminal for this receipt | No combination; changed content under the same source snapshot ID conflicts and is audited |

Federated aggregate exchange is aggregate-only, at-least-once and separately auditable. It does not share owner databases or transfer official indicator authority.
