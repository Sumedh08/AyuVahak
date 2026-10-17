# Volume IV Data Ownership

The matrix lists only durable concepts proposed by the Volume IV design. It is not a schema plan and authorizes no migration. Existing Volume I–III ownership remains unchanged.

| Concept | Owner | Source and authority | Mutable / lifecycle | Retention and privacy |
|---|---|---|---|---|
| Notification projection entry/cursor | Platform notification capability | Derived from eligible committed owner events; owner state remains authoritative | Append/rebuild within bounded scope sequence; old entries expire | Short bounded replay window; opaque resource references, no patient/clinical payload; deletion by retention policy |
| Deployment identity | Platform trust configuration | Locally provisioned deployment instance identity | Rotatable/config-versioned; active identity unique among live deployments | Retain current and audit history; technical identifier, no health data |
| Federation peer profile | Phase 9/platform trust configuration | Administrator-approved authenticated peer and explicit operation/scope grants | Versioned `ACTIVE`, `SUSPENDED`, `REVOKED`; revocation blocks new attempts/retries | Retain revisions/audit; secret references only, never secret bytes |
| Federated operation | Calling owner context, with shared technical metadata only where the established platform convention permits | Named owner intent, partner/config version, operation ID, status and sanitized outcome | State machine in context that owns the business intent; technical attempt metadata cannot own semantic result | Retain per existing owner/audit policy; no patient context or raw payload by default |
| Federated message receipt | Receiving owner context | Authenticated peer, operation/message ID and payload fingerprint | Immutable dedup receipt with recorded outcome; retention at least the replay window plus audit policy | Minimal opaque IDs/fingerprint; no patient/clinical content |
| Remote directory/capacity evidence projection | Consuming owner (Directory discovery or Matching/Transfer evidence projection) | Remote owner source, source version/policy, observed/received time, validity | Replace only by newer compatible source version; expires to stale/unknown | Bounded cache/projection; source attribution required; never local authority |
| Reporting measure snapshot | Each source business owner | Scoped measure computed within its own persistence boundary | Immutable snapshot/watermark for requested measure/scope/period | Retain according to source owner policy; approved aggregate only, no case/patient IDs |
| Indicator definition/version | Analytics | Deployment-approved measure formula and policy configuration | Immutable version with effective interval; changes create new version | Retained with audit; bounded dimensions, no free text or patient attributes |
| Aggregate snapshot/revision and captured measure inputs | Analytics | Versioned owner reporting snapshots and completeness evidence | Immutable revision; provisional/finalized/corrected via linked new revision | Retain exact bounded aggregate inputs/cutoffs needed for reproducibility; no raw events/case/patient IDs; potentially sensitive aggregate, suppression applied |
| DHIS2 mapping version | Analytics adapter configuration | Administrator-approved target metadata and mappings | Versioned, effective-dated, deactivation is explicit | Retain mapping/audit history; external UIDs are target references, not internal identity |
| DHIS2 export operation | Analytics | One immutable snapshot revision plus partner/mapping version | Bounded lifecycle through acknowledgment/partial/unknown/reconciliation/permanent failure | Retain sanitized metadata and per-value outcomes; do not archive full request/response by default |

## Non-owned or prohibited data

- Analytics does not retain patient-level, referral-level, transfer-level or clinical records to calculate aggregates.
- Federation does not copy private tables or mirror every owner aggregate.
- Notifications do not retain a permanent event-sourced history.
- Phase 9 technical metadata does not become a global business workflow ledger.
- DHIS2 is not an AyuVahak database and its metadata does not define AyuVahak identity.
- No concept stores access tokens, passwords, private key material, patient GPS, raw IPS, raw FHIR, or arbitrary DHIS2 payload archives.

## Source facts and retention boundaries

Owner reporting snapshots are minimized, scope-limited interfaces. Analytics persists only measures, cutoffs, quality/completeness and derived results needed to reproduce the approved indicator and its revisions. DHIS2 export records retain the target tuple, operation identity, config/mapping version, sanitized response classification and per-value status needed for reconciliation. Exact retention periods are deployment configuration bounded by the approved retention policy; they do not block the design because no data is retained beyond the stated purpose.
