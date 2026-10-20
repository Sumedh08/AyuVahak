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

## Owner-scoped restore reapplication currently implemented

The executable reapplication boundary covers two owner-local actions: Platform's delivered
outbox purge/hold state and Referral's closed-case identifier-access restriction/hold state. Each
owner exports a payload-free manifest after the backup checkpoint and applies only its own
manifest to its own schema. Stop owner writes before the checkpoint and keep them stopped through
manifest export so the backup and manifests describe one stable recovery boundary. Platform owns
the readiness contributor, which reads the configured restore identifier and requires
completion receipts from both `platform-eventing` and `referral`; it does not invoke replayers or
change any owner data. Set `AYUVAHAK_RETENTION_REQUIRED_RESTORE_ID` on the restored deployment
before traffic is admitted, export each owner's manifest from the pre-restore source, reapply it
through that owner's restricted operator route while the restored deployment remains out of
service, and wait for the readiness gate to report complete. A missing owner receipt keeps
readiness down. Platform reapplication treats a purge receipt as applying to the logical event:
it removes an older restored delivery generation, but rejects a restored generation newer than
the receipt and rejects active holds. The restore acceptance now also verifies an active Referral
hold is absent from the older backup, restored from the manifest, and still active before
readiness becomes healthy. The owner routes are:

- `POST /api/v1/platform/event-deliveries/retention-reapplication/export`
- `POST /api/v1/platform/event-deliveries/retention-reapplication`
- `POST /api/v1/referrals/retention-reapplication/export`
- `POST /api/v1/referrals/retention-reapplication`

Referral's identifier restriction is an access-control overlay, not physical pseudonymization:
closed-case responses and patient-context disclosure are blocked while immutable submitted
revisions and audit history remain intact. Physical PatientReference rewriting/deletion is not
implemented. The application currently permits automated register actions only for
`PLATFORM_EVENT_OUTBOX/PURGE` and
`PLATFORM_NOTIFICATION_PROJECTION/EXPIRE_PROJECTION`; unsupported automated action/category
pairs fail production-profile validation. Referral restriction is a separately authorized
owner operation, not a generic `MINIMIZE_OR_PSEUDONYMIZE` register action. No automatic physical
minimization or pseudonymization action is implemented for any category. Other owner data
categories do not acquire purge, minimization, or restore behavior from the register; they require
their own reviewed owner implementation or remain under `OWNER_REVIEW`.

## Owner-by-owner implementation and restore evidence

This table distinguishes executable owner behavior from ordinary backup survival and policy
declarations. A retained row in the backup is not evidence of an expiry worker. References name
the executable evidence currently present; `BackupRestoreAcceptanceIT` uses disposable local
PostgreSQL/PostGIS containers and unmistakably synthetic values.

| Category | Synthetic record/action and observed post-state | Restore behavior and evidence | Classification / boundary |
|---|---|---|---|
| Identity/grants | Active `SYN-RESTORE-OPERATOR` Directory grant is seeded; no expiry or revocation action is run. | Grant is part of the logical dump and survives restore. No grant purge/restriction manifest exists. | **Config/evidence-only.** Deployment authority owns grant lifecycle; no automatic grant expiry is implemented here. |
| Audit | `SYNTHETIC_BACKUP_FIXTURE` audit record is seeded; no deletion/minimization action is run. | Audit record survives restore and is counted afterward. Audit is not rewritten by either manifest. | **Retained for history invariants.** No audit purge/minimization action is implemented; any future lifecycle must preserve required accountability and integrity. |
| Idempotency | Completed synthetic Platform grant, Transfer, and Transport command receipts are seeded. | All three receipts and their result references survive restore. The acceptance checks each restored owner receipt. | **Retained for unresolved/history invariants.** There is no generic receipt purge worker in this slice; any expiry must be owned by the relevant context and preserve retry/dedup horizons. |
| Event/outbox | A delivered synthetic internal event is included in the backup; after backup its delivery rows and event are purged and a purge receipt is written. | Platform manifest reapplies purge before readiness; the restored event is gone and its purge receipt remains. | **Executable.** `OutboxTransactionPostgresIT.anActiveHoldBlocksOutboxPurgeUntilTheOwnerReleasesIt` and `platformManifestReappliesReleasedHoldAndCompletedPurgeBeforeReadiness` cover hold-aware purge/reapplication; `BackupRestoreAcceptanceIT` proves post-backup purge reapplication before readiness. |
| Notifications | Synthetic scoped notification hints expire under the configured projection/dedup retention. | A replay query after expiry returns refresh-required and prunes the expired scope even if no later append triggered cleanup. | **Owner-provided expiry.** `NotificationProjectionPostgresIT.expiresProjectionOnReadEvenWhenNoLaterEventTriggersPruning` covers query-time expiry. No separate restore manifest is needed for cursor replay: expired history is rejected from the restored projection on read. |
| Directory | A synthetic organization is dumped; its name is changed after the checkpoint. | Restored organization has the checkpoint name; the post-checkpoint business mutation is absent. | **Config/evidence-only.** This is backup-point behavior, not Directory retention or deletion. No Directory purge/minimization or retention replayer is implemented. |
| Capacity | `CapacityObservationCommandsApiIT.staleEvidenceIsNotReportedAsCurrent` and `validityExpiryIsExclusiveAndDistinctFromStaleEvidence` submit synthetic expired/stale evidence and assert it is not returned as current/positive capacity; Transfer acceptance also proves stale Capacity cannot reserve/promote a Transfer. No post-backup purge is performed. | Backup survival does not make expired evidence current: the owner evaluates validity at read/decision time. This restore fixture does not seed a Capacity observation. | **Owner-provided validity expiry.** Expiry changes operational meaning to stale/unknown; it is not historical deletion. Evidence retention/deletion and restore reapplication remain deployment-policy and owner-implementation gaps. |
| Referral | A closed synthetic Referral is restricted after backup; a second closed Referral receives an active `UNRESOLVED_RECONCILIATION` hold after backup. | Referral manifest reapplies both restriction and hold while readiness is DOWN; readiness becomes UP only after both owner receipts exist. The restricted closed case has an access-control overlay; the held case remains held and is not restricted. | **Executable, limited.** `ReferralDraftCommandsApiIT` covers authorized hold/release and restore overlay. The backup acceptance proves post-backup restriction and active-hold reapplication. This is not physical PatientReference minimization. |
| Matching | No Matching recommendation is mutated for retention in this fixture. Recommendation evidence is normally kept as versioned decision history. | No Matching-specific reapplication receipt or deletion manifest exists; any seeded recommendation would only survive the database checkpoint. | **Config/evidence-only.** Deployment policy must respect Transfer/reconciliation/audit dependencies; no purge/minimization worker is implemented. |
| Transfer | Synthetic origin journey contains an accepted receiving decision and a Transfer in `AWAITING_RESERVATION`; a completed command receipt is seeded. | Transfer, decision/assessment evidence, and command receipt survive restore. A synthetic origin federation attempt in `OUTCOME_UNKNOWN` is also retained. | **Retained for unresolved/history invariants.** No Transfer purge/minimization/reapplication worker exists; unresolved coordination must not be discarded. |
| Transport | Synthetic `REQUESTED` transport record and completed command receipt are seeded against the Transfer. | Request state and receipt survive restore. | **Retained for unresolved/history invariants.** No Transport expiry/deletion worker or restore manifest exists. |
| Phase 9 exchange | No exchange attempt/configuration is changed by this restore fixture. | No Phase 9 owner retention manifest or exchange-specific reapplication receipt is verified here. | **Config/evidence-only.** Partner trust, credentials/references, and in-flight outcomes require Phase 9 owner policy; this slice does not claim their expiry/reapplication. |
| Phase 11 federation | Synthetic origin receiving operation is `OUTCOME_UNKNOWN` with a coordination-only payload. | Attempt and stable operation identity survive restore and remain `OUTCOME_UNKNOWN`. | **Retained for unresolved/history invariants.** No federation receipt purge/reapplication worker exists; unknown outcomes must remain recoverable. |
| Phase 12 Analytics | Synthetic DHIS2 export is `OUTCOME_UNKNOWN` and owns a target-tuple claim. | Operation and tuple barrier survive restore; newer corrections remain blocked by that unresolved state. `AnalyticsDisclosureReservationPostgresIT` covers stable disclosure lineages across merge/split and policy rollover; `Dhis2AggregateExportPostgresIT` covers unknown outcomes and tuple barriers. | **Retained for unresolved/history invariants.** No Analytics purge/minimization/reapplication worker exists; disclosure lineage and unresolved exports must be retained under owner policy. |
| Telemetry | No telemetry records are stored in the application business database or changed in this fixture. | External telemetry-store backup/restore is not exercised. | **Config/evidence-only.** Deployment operator configures telemetry retention and separately proves its provider's restore/retention behavior. |
| Backups and restore artifacts | Synthetic custom-format `pg_dump` is restored into a separate clean PostgreSQL/PostGIS container; temporary dump is removed after the test. | Two-database boundary, migrated state, owner manifests, readiness transition, and authorized Directory read are checked. | **Executable acceptance evidence, deployment-specific operations.** This does not prove scheduled/off-host backup, encryption, WAL/PITR, or production storage lifecycle. |

The restore acceptance performs **no physical minimization**. In particular, Referral's
restriction is an access-control decision and leaves the PatientReference in owner history. The
configuration validator rejects `MINIMIZE_OR_PSEUDONYMIZE`, `RESTRICTED_TOMBSTONE`, or other
automated actions for which no category-specific expiry adapter exists. A deployment must select
`OWNER_REVIEW` for unsupported categories, supply its approved schedule and hold authority, and
not treat that declaration as executable deletion. Before live use, each such owner must either
provide reviewed expiry, hold, and post-backup reapplication behavior or the deployment must
document why records remain retained under current unresolved/history invariants. Neither the
register nor this acceptance record supplies a legal retention duration.

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
