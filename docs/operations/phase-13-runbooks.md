# Phase 13 operator runbooks

**Status:** Local operator guidance template. A deployment must assign named roles,
contact paths, thresholds, evidence stores, and approved recovery procedures before live use.
These runbooks do not authorize an external-system action or replace the deployment's
incident, privacy, legal, or clinical governance.

## Shared operating rules

- Record incident time, deployment identity, release/source revision, affected component,
  decision owner, and sanitized evidence reference. Do not copy tokens, patient data,
  partner payloads, or secret values into incident notes.
- Separate liveness from readiness. Do not restart a healthy process repeatedly because an
  optional peer is unavailable. Do not admit writes when a required local dependency,
  migration state, audit requirement, or restore-retention gate is unsafe.
- Prefer containment and forward recovery. Do not run ad hoc SQL against another context's
  schema, disable authorization/audit, delete unresolved receipts, or infer failure from a
  timeout.
- Confirm the deployment's authority and approved local endpoint before any operational
  action. If that authority or endpoint is unclear, stop and escalate.

## Database outage, corruption, and restore

**Detect:** Required database readiness fails, connection pool remains exhausted, integrity
checks fail, or restore verification finds an owner invariant mismatch.

**Contain:** Stop mutation admission at the approved ingress and keep all application
replicas out of service if the database is corrupt or migration state is unknown. Preserve
the original database and sanitized diagnostics. Do not promote a replica as if it were a
backup.

**Owner and fallback:** The database/recovery owner leads. During a transient outage, report
unavailability; do not queue business mutations in memory. For recovery, restore only to an
isolated clean target from the deployment's verified backup/WAL set. Recover key and
configuration references from the approved secret/configuration provider, not from a database
backup.

**Recovery:** Validate the selected restore point and schema. Export post-backup retention
actions from the authoritative source while owner writes are stopped and retain the source
checkpoint. Reapply each available owner manifest through that owner's procedure. In this
implementation, the tested application gate covers Platform outbox purge state and Referral
identifier-access restrictions; other owner categories do not yet have destructive
reapplication adapters. If the deployment's completed retention actions exceed that
implemented scope, do not admit the restored instance until an owner-approved reconciliation
is completed.

**Validate:** Require all configured restore receipts and readiness, successful Flyway
validation, representative scoped reads, audit/idempotency/outbox checks, and unresolved
federation/Analytics operation barriers. Record actual elapsed recovery and the data-loss
boundary against deployment-owned RTO/RPO. Resume admission only after the recovery owner
and application owner approve the evidence.

**Escalate:** Failed integrity, missing archive/key, unavailable retention evidence, or an
RPO/RTO miss goes to the named recovery authority; keep traffic stopped until a safe path is
approved.

## Identity provider outage

**Detect:** OIDC issuer/JWKS retrieval or token validation fails, or the identity provider's
operator reports an outage/key incident.

**Contain:** Do not bypass signature, issuer, audience, expiry, or grant checks. New or
invalidly authenticated requests remain denied. Keep database liveness separate from the
IdP. Follow the deployment's bounded JWKS cache policy; do not extend it manually without
security-owner approval.

**Recovery and validation:** Confirm the trusted issuer and key rotation through the
configured provider, then verify a synthetic/operator-approved token and a denied invalid
token through local health and protected API checks. Escalate suspected key compromise through
the identity/security authority and follow the credential rotation runbook.

## Secret, key, and certificate compromise or rotation

**Detect:** Provider alert, expiration, unexpected key fingerprint, suspected exposure, or a
credential rejected by its trusted peer.

**Contain:** Notify the credential owner. Suspend the affected partner/credential through the
approved owner control when compromise is plausible; stop new dependent operations. Preserve
bounded operation identities and outcomes. Never copy the secret into logs or incident
records.

**Rotate:** Resolve a new secret reference from the approved provider, apply the documented
overlap window, verify the new identity against a controlled local peer, and revoke the old
credential after confirmation. On provider outage or missing secret, fail the protected
operation closed; do not place secret material in the database or environment dump as a
workaround.

**Validate and escalate:** Check issuer/key/certificate identity, allowed partner scope,
revocation bounds, and redaction. Escalate suspected production disclosure to the deployment
security and incident authorities; preserve only approved, minimized evidence.

## Phase 9 exchange and federation outage or unknown outcome

**Detect:** Peer unavailable, timeout, partner trust failure, queued age threshold exceeded,
or an operation is marked pending/unknown/reconciliation-required.

**Contain:** Keep owner-local state authoritative. Do not turn timeout into rejection or
success, do not send a new operation identity for the same logical action, and do not retry a
possibly committed operation unless its frozen contract explicitly allows safe replay. On
peer suspension/revocation, stop queued sends and retries.

**Recover:** After the peer and trust are available, query or replay using the same stable
operation ID and fingerprint through Phase 9. If the outcome cannot be established, retain
the unknown state and block conflicting work under the owner invariant. A human may reconcile
only through the documented owner command with audit evidence.

**Validate:** Confirm the peer identity, exact operation/scope permission, prior receipt,
owner state, and absence of duplicate business effects. Escalate ambiguous authority,
fingerprint conflict, or suspected peer compromise to both local trust and domain owners.

## Stuck outbox or reconciliation worker

**Detect:** Durable queue age/attempt count crosses the deployment's configured threshold or
the worker repeatedly exits.

**Contain:** Determine whether the issue is local persistence, a revoked/unavailable peer, or
a handler defect. Keep events and idempotency receipts intact. Do not bulk-reset attempts or
mark work delivered to clear a dashboard.

**Recover:** Fix the local dependency or reviewed handler/configuration issue, then restart a
bounded worker. Existing event IDs and owner deduplication must be retained. Do not hold
database transactions open across network calls.

**Validate:** Check one bounded batch, durable lag trend, dedup/receipt state, and owner
invariants. Escalate persistent poison events or growing backlog to the owning context and
operations lead with sanitized event type/result class only.

## Audit persistence failure

**Detect:** Required audit write fails or the audit integrity/readiness signal fails.

**Contain:** Protected mutations that require audit remain failed closed. Do not switch to
unlogged writes, edit the chain, or treat application logs as audit evidence.

**Recover:** Restore the audit persistence dependency using the approved database process.
Reconcile only with the audit owner; preserve immutable evidence and chain/checkpoint rules.

**Validate:** Run the authorized audit integrity check and a synthetic protected action in a
controlled local acceptance environment. Escalate any gap, truncation, or unexplained record
to the security and records owners before resuming protected mutations.

## Notification and SSE degradation

**Detect:** Projection lag, bounded client queue overflow, excess connections, or stream
disconnects exceed the local operating threshold.

**Contain:** Clients must refresh through their normal authorized owner API. Reduce or reject
new subscriptions at the configured bound; disconnect slow clients after the documented
overflow policy. Do not increase queues without a resource analysis.

**Recover and validate:** Restore the durable projector/worker and allow it to scan durable
state. Verify current owner queries and bounded cursor replay. Reauthorize on reconnect;
notification delivery never grants resource access. Escalate cursor-epoch mismatch or
cross-scope evidence to the security owner.

## Disk or connection exhaustion

**Detect:** Database free space, WAL/archive backlog, pool saturation, or process resources
reach the configured warning/critical threshold.

**Contain:** Apply the deployment's admission/load-shedding policy and reduce nonessential
workload. Preserve audit, unresolved idempotency/federation/export state, and required WAL.
Do not manually delete database files, logs needed for an incident, or unresolved operation
rows.

**Recover:** Database and operations owners identify the responsible bounded workload, free
only approved disposable artifacts, and restore capacity. Check replica/pool limits and
backlog before increasing concurrency.

**Validate:** Confirm the database is stable, required work resumes without duplicate effects,
and retention/backup integrity remains valid. Escalate repeated exhaustion for capacity
re-baselining.

## Failed or interrupted migration

**Detect:** Flyway validation/startup fails, advisory migration lock contention persists, or
the expected schema version is not reached.

**Contain:** Keep the target release out of ingress; stop other replicas from starting against
an unknown schema. Preserve migration history and sanitized logs. Do not use `repair` or a
down migration as a generic retry.

**Recover:** Follow [Production upgrade and migration recovery](../development/production-upgrade-migration.md):
choose a tested forward repair or verified restore. Ensure a single controlled migrator and
pre-upgrade backup evidence.

**Validate:** Confirm Flyway history, owner schema invariants, application startup/readiness,
and representative owner paths on an isolated copy before resuming traffic. Escalate any
partial migration with no proven repair path.

## Vulnerability finding or dependency incident

**Detect:** Local dependency/artifact scan, vendor advisory, exploit report, or incident
indicates an affected component.

**Triage:** Build an inventory of affected versions; assess severity, reachability, exposure,
known exploitation, available fix, and deployment context. Assign an owner and due date. A
finding is not dismissed because it is absent from KEV or because a score is low.

**Contain and remediate:** Apply a reviewed compensating control or upgrade/patch through the
normal tested release path. A temporary exception names the exact component/finding, owner,
rationale, controls, approver, and expiry/review date. Do not create an indefinite exception or
claim zero risk.

**Validate and escalate:** Re-run the local inventory/scan and affected tests, verify artifact
identity, and close or renew the exception through authorized review. Escalate critical
exploitable findings under the deployment's incident timing policy.

## Suspected breach or sensitive-data exposure

**Contain:** Follow the deployment's incident command. Stop the affected integration or
access path when safe, preserve approved forensic evidence, and avoid destructive cleanup that
could destroy evidence. Do not copy real patient data or secrets into this repository, issue
tracker, or general logs.

**Assess:** Security/privacy authorities determine scope, affected owner, retention/hold,
required notifications, and applicable jurisdictional duties. This runbook does not make
those legal determinations.

**Recover and validate:** Rotate affected credentials, restore a trusted version if needed,
verify grants/trust/configuration and owner invariants, and only then resume service. Escalate
to the named incident/privacy authority; document the authorized decision and validation
without sensitive payloads.

## Unsafe production configuration

**Detect:** Production startup rejects a missing/unsafe setting, management exposure, origin,
issuer, limit, SLO declaration, secret reference, or retention register.

**Contain:** Keep the instance out of ingress. Do not silence the validator, copy a development
profile, enable wildcard credentialed CORS, trust-all TLS, expose sensitive Actuator surfaces,
or substitute a test IdP/secret.

**Recover:** The accountable deployment operator supplies reviewed, secret-free references and
approved values using the selected profile. Restart through the normal controlled deployment
path.

**Validate:** Confirm startup validation, local health/readiness and management isolation;
verify effective configuration without dumping secrets. Escalate any mismatch between profile
and actual topology to the security and operations owners.

## Operator-specific values to complete before live use

For every deployment, name an incident commander, recovery/database owner, identity owner,
security/privacy contact, domain escalation path, notification route, RPO/RTO, alert thresholds,
backup/WAL tool, approved secret provider, retention schedule and evidence location. Store
those deployment-specific values in the deployment's controlled operations system, not in this
generic repository template.
