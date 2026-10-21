# Phase 13 Acceptance Record

**Phase 13 disposition:** COMPLETE + FROZEN — independent review found zero BLOCKER/MAJOR. The local implementation freeze is `57b949ab8c5fa28e95243e83df0910731dbec800`; the separate program-state follow-up is `809ff8e8c5ba79c5c8ceb9ec7d0e7b6985f3619f`.
**Current program note (2026-10-22):** This record captures Phase 13 acceptance and remains unchanged as an evidence record. Phase 14 productization is now authorized and in progress under the separate Volume V master prompt; the historical “not started” statements below describe their original dated checkpoints. Volume V is not complete.
**Assessment date:** 2026-10-21.
**Acceptance target:** local-only, synthetic data, frozen Phase 13 specification.

**Benchmark revisions:** initial mixed workload `068d2bdd36f9b36ba1cd65ecab6d46c741bc3750`; final
reviewed-candidate harness `13e9d176ac99897c393fb2ee74248340c292136b` on
`codex/phase-13-production-hardening`. The dated sections below preserve earlier failures and intermediate dispositions. The final remediation section and freeze disposition supersede those statuses. The canonical Java 25 clean verification passed at `c9bc4a2`; only test/benchmark code changed afterward, and the application JAR remains byte-identical. Both representative workload profiles passed, and the final independent review and Phase 13 freeze are complete.

## Historical pre-remediation Java 25 gate

This records the original stop condition and is retained as failure history; it is superseded by
the authorized local Java 25 retrieval recorded in the current remediation disposition below.

The continuation started at `6e231d8a5e4b1a9e58f99f15b85bc66c0cad623e`; `a511e45` is the earlier
SPDX implementation commit, followed by documentation commits `d9e5666` and `6e231d8`. The local
`JAVA_HOME` target is inaccessible. The only executable found on `PATH` is Temurin Java 17.0.8,
while `pom.xml` requires Java 25. `java -version` confirmed Java 17.0.8 and `mvnw -version` exited
before Maven startup with the invalid `JAVA_HOME` error. The local installation search found no
accessible Java 25 runtime. No JDK was downloaded. Per the remediation gate, implementation and
current-tree verification stopped here:

`PHASE 13 REMEDIATION BLOCKED — JAVA 25 TOOLCHAIN UNAVAILABLE — PHASE 14 NOT STARTED`

## Approved ticket graph

The human-approved 11-slice graph is stored locally under
`.scratch/phase-13-production-hardening-dpg-readiness/issues/`. It covers portable production
profiles, provider-neutral secrets, HTTP/abuse controls, SLI/SLO and capacity evidence, migration
recovery, replica/worker/shutdown behavior, owner retention, backup/restore, release evidence,
operator/DPG documentation, and integrated acceptance/freeze. No external tracker was used.

## Historical pre-remediation implementation evidence

The following table reflects the earlier assessment before the continuation remediation and is
retained for traceability. Current outcomes are listed in “Remediation disposition” below.

| Slice | Result | Evidence / limit |
|---|---|---|
| P13-01 Production profiles | Implemented | Fail-closed profile validation and local configuration tests; no production deployment was performed. |
| P13-02 Secret boundary | Implemented | Provider-neutral reference resolution and local fake-provider/rotation tests; no real provider or credential was used. |
| P13-03 HTTP and abuse controls | Partial | Local management, HTTP, CORS, and per-process actor/traffic-class rate-limit controls/tests exist. Current budgets do not cover all frozen principal/service, tenant/scope, partner, and operation dimensions, and per-process limits multiply across replicas. |
| P13-04 SLI/SLO and capacity | Implemented, deployment evidence partial | Bounded SLI catalogue and configurable declarations exist; no universal SLO or external monitoring operation is claimed. |
| P13-05 Migration recovery | Implemented | Controlled migration/readiness behavior and PostgreSQL migration integration tests; production upgrade evidence remains deployment-specific. |
| P13-06 Replica/worker/shutdown | Partial | Bounded shutdown and duplicate PostgreSQL outbox-worker claim tests exist. A two-process/two-application-replica acceptance run is not yet evidenced. |
| P13-07 Retention | Partial | Owner-scoped Platform outbox purge and Referral identifier restriction/reapplication are implemented. Other matrix owners do not yet have complete purge/minimization adapters or restore replayers. |
| P13-08 Backup/restore | Partial | Isolated synthetic `pg_dump`/`pg_restore`, Flyway, restored application, Platform/Referral retention reapplication, and an unresolved Analytics export barrier are exercised. The post-backup Directory update is intentionally lost at the dump recovery point. The fixture omits idempotency and representative Transfer, Transport, and federation invariants required by the frozen acceptance spec. |
| P13-09 Release evidence | Partial | Pinned cdxgen 13.4.0 generated an SPDX 3.0.1 SBOM from the local runtime dependency tree; normalized output passed the pinned official JSON Schema and SHACL model plus local SPDX License List 3.29.0 validation. The actual AyuVahak JAR digest is bound. A local manifest/provenance/signed bundle was generated with an ephemeral synthetic signer and verified; the release remains ineligible because no reviewed dependency vulnerability inventory is bound. Release-evidence helpers have 19 passing Python tests. |
| P13-10 Operations/DPG evidence | Partial | Local runbooks, ASVS 5.0.0 matrix, standards register, and evidence-based readiness matrix are present; governance and live deployment evidence remain open. |
| P13-11 Integrated acceptance | Not complete | A prior canonical clean verification passed, and local SPDX 3.0.1 generation/validation now passes. The repeat at `a511e45` failed before Maven startup because `JAVA_HOME` was unavailable. On the current continuation, only Java 17.0.8 is accessible while the project requires Java 25, so the required clean verification gate stopped before remediation. Complete retention coverage, true multi-replica acceptance, measured deployment-profile workload baselines, vulnerability inventory review, and blocker remediation remain outstanding. |

## Historical backup and restore acceptance

The acceptance uses two local PostgreSQL/PostGIS 18.6 containers with synthetic fixtures only. The
target is recreated from `template0`, restored from a custom-format `pg_dump`, and started with a
local fake JWKS endpoint. The fixture covers a grant, Directory organization, audit record, delivered
outbox event, Referral restriction, and unresolved DHIS2 export/target-tuple claim. A post-backup
retention action must be reapplied before readiness becomes healthy. A privacy marker is checked
against retention manifests, audit, event, and Analytics export operation state.

The test declares synthetic 15-minute RPO and RTO targets. In the recorded canonical clean run on
2026-10-20, the interval from the completed logical dump (the last recoverable point) to simulated
disaster declaration was 0.195s; restore elapsed time from disaster declaration through target
database recreation, dump transfer/restore, retention reapplication, readiness, and authorized read
was 8.121s. Both were within the synthetic targets. A later repeat canonical command at HEAD
`a511e45` could not start because the configured `JAVA_HOME` target was unavailable; Java 17 was
accessible, but incompatible with this Java 25 project. These values are prior-run evidence, not a
verification of that attempt. The method is a logical
snapshot; it does not exercise WAL/PITR, scheduled backups, encryption, or off-host storage. No general production
RPO/RTO claim follows from this test.

## Historical local verification before remediation

- Canonical `.\mvnw.cmd -B -ntp clean verify`: **BUILD SUCCESS** in 19m38s using Java 25.0.4.1,
  Maven 3.10.0, Spring Boot 4.1.1, and PostgreSQL 18.6 Testcontainers; Flyway validated/applied
  92 migrations. Surefire: 479 tests, 0 failures, 0 errors, 0 skips. Failsafe: 408 tests,
  0 failures, 0 errors, 0 skips. Total: 887 tests across 103 unit suites and 31 integration
  suites. These are local synthetic acceptance runs, not production deployment evidence.
- `BackupRestoreAcceptanceIT`: 1 Failsafe test passed in the canonical clean run (0 failures,
  0 errors, 0 skips; test time 24.31s). It demonstrated post-backup business-state loss, retention
  reapplication before readiness, restored `OUTCOME_UNKNOWN` tuple-barrier preservation, and an
  authorized read.
- `ProductionFlywayMigrationStrategyIT`: 4 integration tests passed in the canonical clean run.
- Focused retention regression suites also passed earlier: Event delivery 6, outbox PostgreSQL 14,
  and Referral draft API 34.
- Local release-evidence Python tests: 19 passed, 0 failures/errors/skips in 4.99 seconds.
- Local SBOM generation/validation: cdxgen 13.4.0 (official pinned Windows AMD64 asset SHA-256 `0b4a6ec411a70127d72e2f6fc45277f03fa64d4237a40325d0ff117634b76375`) generated 195 runtime package nodes and 133 declared-license relationships for the local JAR. The SPDX 3.0.1 output SHA-256 is `0e5230a50e0dd4615b7b0fbf4beb87a3582fdd943e1a10498d1505130d3f5a76`; the application JAR SHA-256 is `5ae4598d12c8f660915ab556869becad57b93cacf833d5bdcf89a638037fc9f4`. Validation used pinned official schema, SHACL model/context, and SPDX License List 3.29.0 assets. Maven ran offline with repository-owned credential-free settings and a preverified cached wrapper distribution; cdxgen was loopback-only and its execution trace contained no external host activity. This is local acceptance evidence, not a published release.
- Local manifest/provenance/evidence-pack check: a local manifest revalidated and bound the SPDX SBOM to the application JAR. A provenance record and bundle containing the JAR, manifest, SBOM, validation report and provenance verified with a two-day ephemeral synthetic PKCS#12 identity (fingerprint `2c381e4e5183906b37aecbd8b70338a121f64992a9373eb91711281cf0e62f0a`; bundle SHA-256 `dd0495d594ccd3dd16da482b09ca0b9a122f32f9566a66e8b545423be83de5e7`). The manifest remained release-ineligible because it had no reviewed dependency vulnerability inventory or production signer. The bundle stayed under ignored `.scratch` and was not published.
- The canonical full clean verification passed before the latest local Python/doc-only release-evidence changes. Its repeat at `a511e45` exited before the Maven build because `JAVA_HOME` was unavailable; Java 17 was accessible but cannot compile the Java 25 project. At current HEAD `6e231d8`, no accessible Java 25 runtime was found and remediation stopped before a current-tree clean verification. No JDK was fetched. The frozen integrated Phase 13 acceptance gate remains incomplete because owner retention/recovery coverage, two-replica acceptance, measured workload evidence, and vulnerability-inventory review are still missing.

## Historical readiness and DPG evidence

The production readiness matrix currently reports 0 READY, 18 PARTIAL, 1 MISSING, and 2
DEPLOYMENT-SPECIFIC areas. Local implementation changes improve evidence but do not establish a
production environment's operation, support, or legal configuration.

The DPG matrix remains evidence-based. It does not assert registration, certification, legal
compliance, project ownership, or licensing. Governance evidence and the license decision remain
unresolved; no nomination or submission was made.

## Historical review and freeze gate before remediation

The independent reviews recorded the following open findings at that earlier checkpoint. This table
preserves that history; the final dispositions below supersede its point-in-time status.

| Severity | Finding | Resolution |
|---|---|---|
| BLOCKER | Owner retention/restore coverage was considered incomplete outside Platform outbox and Referral restrictions. | Historical finding; closed as stale after review of the complete 16-category owner register and executable-action evidence below. |
| BLOCKER | Two-application-replica acceptance was not evidenced; worker-claim tests did not prove the frozen multi-replica scenario. | Historical finding; closed by the accepted two-packaged-process failover acceptance and current Profile B benchmark. |
| BLOCKER | No measured synthetic workload baseline was recorded for either supported deployment profile. | Historical finding; closed by the measured mixed Profile A/B workload at final harness commit `13e9d17`. |
| MAJOR | Rate limits lacked required tenant/scope, peer/partner, and operation budgets; per-process buckets could multiply across replicas. | Historical finding; closed by the previously accepted PostgreSQL-shared request-budget evidence. |
| MAJOR | The restore fixture omitted required idempotency and representative Transfer, Transport, and federation invariants. | Historical finding; closed by the previously accepted expanded restore fixture. |
| MAJOR | No reviewed dependency vulnerability inventory was bound to the release manifest; release eligibility remained false. | Historical finding; closed by the previously accepted digest-bound OSV/CISA inventory. |
| MINOR | Independent standards review noted possible divergent-change smells in the combined release-evidence module and large production-profile binding class. | No correctness defect identified; retain as maintainability follow-up. |
| DOCUMENTATION | Earlier readiness text used stale baseline/release claims and inconsistent restore figures. | Corrected in the local documentation follow-up commit. |

At that historical checkpoint, the following conditions were considered unmet:

1. SPDX generation, provenance, local ephemeral signing, manifest binding and signed bundle
   verification now pass for local synthetic acceptance. A reviewed dependency vulnerability
   inventory is not bound to that manifest; release eligibility correctly remains false.
2. Owner retention coverage is incomplete outside Platform outbox and Referral restrictions; the
   frozen owner-by-owner purge/minimization, hold, and restore-reapplication evidence is not complete.
3. Two-application-replica acceptance is not evidenced; worker claim tests do not prove the frozen
   two-replica acceptance scenario.
4. The required measured synthetic workload baseline has not yet been recorded for either supported
   deployment profile.
5. The repeat full clean verification at `a511e45` could not start because `JAVA_HOME` was
   unavailable. At current HEAD `6e231d8`, the accessible Java 17 runtime does not meet the Java 25
   project requirement, and no accessible Java 25 installation was found. The previous clean
   verification is historical evidence only; current verification is blocked.

6. Rate-limit dimensions, the broader restore fixture, and a reviewed vulnerability inventory remain
   incomplete as classified above.

That checkpoint did not authorize a Phase 13 freeze. Its findings are superseded only where the
current evidence below explicitly closes them. Phase 14 remains specification-frozen and
implementation-not-started.

## Historical safety and scope record

All acceptance targets are local and synthetic. No public healthcare/DHIS2/FHIR system, cloud
resource, external vulnerability target, public registry, production secret, real health data, DPG
portal, or remote Git write was used. No Phase 14 code, dependencies, tickets, or optimization work
was added.

## First remediation disposition — 2026-10-20 (historical snapshot)

### Toolchain and repository

- Starting remediation HEAD was `5502d286d2c51bd193ce1bff18ecd2cc594788b1`; code remediation commits
  are `10ca0a98bcc048d2af14dc044d3bf0c29c5f2dc8` and
  `0ca93332d1ced1ab26636ae94c911caca68fbd02`. The branch is
  `codex/phase-13-production-hardening`; `git remote -v` returned no remotes.
- Authorized local JDK: Eclipse Temurin 25.0.4.1+1 (Adoptium), retrieved 2026-10-20 from the
  official Adoptium GitHub release asset
  [OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip](https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4%2B1/OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip).
  Archive SHA-256:
  `00C847D804F4A78E9F04F2683FAF14FED898535B177B7FC704486CB0284E9283`; the checksum was
  independently recomputed and matched. It is extracted under the developer-controlled local
  `AppData\Local\AyuVahak\toolchains` directory. No system-wide Java setting changed.
- `java -version` reports `25.0.4.1`, Temurin; `mvnw.cmd -version` reports Maven 3.10.0 and the
  same Java 25.0.4.1 runtime. A new canonical full clean verification ran after remediation at
  code HEAD `0ca9333`; it failed in Failsafe as detailed below. The earlier Java 17/
  invalid-`JAVA_HOME` failure remains historical only.

### Owner retention and restore coverage

The owner-by-owner register at
[`production-retention-register.md`](../development/production-retention-register.md) contains
exactly the 16 frozen categories. Its matrix classifies each as executable, owner-provided expiry,
configuration/evidence-only, or retained for unresolved/history invariants. This is not a claim
that every owner has a physical deletion adapter or a universal lawful retention period. Current
synthetic acceptance exercises Platform outbox purge/hold and restore reapplication; notification
projection expiry; Capacity expiry-to-stale/unknown; Referral restriction and active-hold
reapplication; retained Directory, Transfer, Transport, idempotency, federation and Analytics
state; and preservation of grant/audit history. Categories without an applicable executable
action are expressly recorded as such, rather than implying automatic deletion. No physical
PatientReference minimization or cross-context deletion was introduced.

### Expanded isolated restore fixture

`BackupRestoreAcceptanceIT` passed against separate disposable PostGIS 18.6 source and restore
containers (1 test, 0 failures/errors/skips; 101.0 seconds). The synthetic fixture now covers a
grant, audit evidence, Platform/Transfer/Transport idempotency receipts, delivered outbox state,
Directory organization, Capacity observation/reservation, Referral restriction and hold,
Transfer lifecycle/acceptance, Transport request, federation `OUTCOME_UNKNOWN`, Analytics
disclosure lineage/reservation, and an unresolved DHIS2 export/target-tuple barrier. It restores
into a clean database, validates Flyway and authorized reads, reapplies retention manifests before
readiness, and verifies the unresolved barriers remain. Measured RPO was `PT4.5290279S`; RTO was
`PT1M3.3759852S`; both beat the test-only 15-minute targets. The test intentionally loses a
post-backup synthetic mutation. This is logical-dump evidence only; it does not prove WAL/PITR,
scheduled/off-host/encrypted production backup, or legal retention compliance.

### Actual application replicas and rate limits

`TwoApplicationReplicaWorkerAcceptanceIT` runs two packaged AyuVahak JVMs on separate local ports,
with separate pools and one disposable shared PostgreSQL business database. It exercises duplicate
outbox-worker claims, kills the replica holding a controlled in-flight projection, verifies lease
recovery on the surviving process, restarts a replica, and checks a delivered event is not repeated.
The three test methods passed (0 failures/errors/skips; 294.4 seconds) on the targeted run, but the
latest full canonical run had a child replica fail during database startup. Therefore targeted
behavior passed, while the full clean verification remains failed and two-replica acceptance is not
an integrated PASS. This process-kill scenario proves only the tested outbox/projection path; other
owner worker claim/dedup behavior has separate PostgreSQL integration coverage. No in-memory state
is treated as durable authority.

The eight `ProductionRequestBudgetStorePostgresIT` tests passed (0 failures/errors/skips; 19.43
seconds). PostgreSQL-shared budgets now cover principal/service, organization/scope and facility
scope, authenticated partner across operation classes, operation/cost class, and anonymous IP
edge limits; tests include principal across client IPs, tenant isolation, partner budgets across
replicas, refill, and operation-class distinction. SSE connection leases are intentionally bounded
per node; the request/subscription budget is shared in PostgreSQL. The per-node connection cap is
not described as a deployment-global count. IP remains supplementary to identity and authorization.

### Profile A/B benchmark at that snapshot: replay-only

The current harness produced successful measurements, but its only measured operation is
`notification_projection_replay_only`. It seeds five synthetic organization scopes and 128 safe
notification hints, then measures 150 replay queries per profile. Facilities, Referrals,
Transfers, Capacity observations, SSE clients, federation peers, Analytics indicators/periods,
and exports are all zero in the declared workload. The numbers therefore do **not** satisfy the
frozen requirement for representative profile workloads spanning the operational contexts.

| Profile | App contexts | p50 | p95 | p99 | Throughput | Errors | Max process CPU | Peak memory | Max active DB connections | DB statement calls |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| A — single site | 1 | 18.8513 ms | 109.5496 ms | 216.7994 ms | 11.5109 ops/s | 0/150 | 0.30282 | 727,846,368 bytes | 1 | 1,801 |
| B — two contexts | 2 | 16.2252 ms | 35.3236 ms | 62.5711 ms | 46.4618 ops/s | 0/150 | 0.31231 | 753,242,448 bytes | 1 | 1,801 |

Both runs used Temurin Java 25.0.4.1+1, PostgreSQL 18.6, the local Testcontainers environment
reporting 8 CPUs and 7,926 MiB, and a warm run with 10 warm-ups × 3 plus 50 measured operations ×
3. These are characterization results for replay only, not production capacity claims. The earlier
benchmark class did not exercise Directory, Capacity, Referral, Matching, Transfer, Transport,
federation operations, Analytics computation or export in its timed mix. This remains an
unresolved **BLOCKER**; no scale claim is made.

### Vulnerability inventory and release evidence

Local metadata already retrieved under the user's explicit authorization was hash-verified:
OSV-Scanner 2.6.0 Windows AMD64 SHA-256
`e0ed7644118b717b028c249ee9d3515024e55e8510747ca08906eb96765354d6`; OSV Maven database archive
SHA-256 `bec867c22ed1121bcd2fa45d2ac0bb8a082789590b57dd7e6987ecc4ad3f852c`, last modified
`2026-10-19T19:48:00Z`, retrieved `2026-10-20T07:00:35Z`; CISA KEV snapshot SHA-256
`b6e27975f6635ecfc546fbd4fd6e444ba63a07ab2abc8876229d77fe083d357d`, retrieved
`2026-10-20T07:00:35Z`. Matching was local/offline; no project dependency manifest, SBOM, JAR or
source was uploaded. The current JAR scan reported zero matches across 180 runtime components; the
inventory records scanner/database/KEV snapshot digests and explicitly states that zero matches do
not prove absence, exploitability or reachability. The JAR SHA-256 is
`77647c88a101dfb4c83963299aeed39d20149b38f39c06b7598ae946f9a5e540`; the scanned CycloneDX input
SHA-256 is `0473eeca528c2d4168639103f7e22d296442edda830f24adf484436dd73702d5`, and the reviewed
inventory SHA-256 is `5938fc09f0ebf690b4ec6482216ad3a20f60dc875b6b5899489db4d509ed6258`. SPDX 3.0.1
validation passed for the same JAR (SBOM SHA-256
`449eb6cca389280d02dd2f258c01e5c39eca2cdaf9d29dd722f0ffdc34c4067b`). A local provenance,
manifest and ephemeral synthetic-signature evidence pack verified with bundle SHA-256
`84D087B3876C92A9C9BCDAFC4685A130565BBC0DD46D09C6C0D6B6BACAADFCB3`; its release disposition
is explicitly ineligible, and the provenance records the failed canonical verify and replay-only
benchmark. No production signing identity is represented.

### Latest canonical clean verification — failed

At remediation HEAD `0ca93332d1ced1ab26636ae94c911caca68fbd02`, the Java 25/Maven 3.10.0 canonical
`clean verify` completed Surefire successfully (488 tests; 0 failures, errors or skips) but failed
Failsafe (406 tests; 1 failure, 2 errors, 0 skips; build exit 1 after 29:02). The failure was the
synthetic `DirectoryCapacityReferralScenarioIT` expecting IPS projection status `AVAILABLE` but
receiving `INVALID`. Errors were a local PostGIS container startup timeout in
`PartnerTrustAdministrationIT` and a child-replica database connection failure during
`TwoApplicationReplicaWorkerAcceptanceIT`. Earlier targeted runs passed the affected partner-trust,
replica and Transfer suites, but those isolated passes do not override the failed full regression.
The acceptance run is therefore not green. No frozen Volume II/III behavior was changed to mask
the failure. A targeted isolated rerun of the same scenario class passed 9/9 in 105.1 seconds; the
separate local PostGIS startup and child-process startup errors have also passed isolated reruns in
earlier attempts. These observations show suite-load instability but do not change the failed
canonical result.

### Gate disposition at that snapshot (superseded by final remediation below)

| Finding/gate | Current result | Evidence / limit |
|---|---|---|
| Owner retention/restore coverage | Classification and acceptance matrix updated | Every frozen owner category appears in the register; only the explicitly supported owner actions are executable. No legal claim or generic deletion is inferred. |
| Expanded restore fixture | PASS | Synthetic owner state, idempotency, hold/restriction reapplication, federation/Analytics unknown outcomes, target tuple barrier, Flyway and authorized read verified. |
| Two application replicas | Targeted PASS; canonical run ERROR | Two packaged processes, shared DB, duplicate claim, process termination, failover and restart passed targeted; one child could not connect to disposable PostgreSQL in the latest full run. |
| Rate-limit dimensions/distributed semantics | PASS for specified shared budgets | Cross-replica PostgreSQL budget tests pass; SSE connection-count cap remains explicitly per node. |
| Profile A/B representative workload | **BLOCKER** | Measured replay-only results above omit required context workloads and dimensions. |
| Reviewed dependency inventory and exact-artifact release binding | PASS as local acceptance evidence; release ineligible | OSV/CISA snapshots, scan SBOM, JAR, SPDX, provenance, manifest and ephemeral signature are digest-bound in the local evidence pack. It is not an eligible or published release. |
| Canonical Java 25 clean verify on remediation HEAD | FAIL | Surefire 488/488 passed; Failsafe 406 tests, 1 failure and 2 errors, 0 skips. See failure categories above and the complete local log. |

No Phase 13 freeze or governance transition is made while the representative profile workload gate
was unresolved at this snapshot. Phase 14 remains specification-frozen and implementation-not-started.

## Final benchmark remediation — 2026-10-21

### Pinned implementation and repeatability

The mixed workload and explicit Analytics queue/backlog observation were first committed at
`068d2bdd36f9b36ba1cd65ecab6d46c741bc3750`. Review then required live authorized SSE streams and
an assertion that they stay connected throughout measurement. The harness now holds two simultaneous
connections per profile (one on each Profile B replica), observes disconnect callbacks, and asserts
that the minimum active stream count remains two before and after every warm-up and measured cycle.
The initial live-stream implementation was committed at `49a6cfd43b89ec0732bacd2ac9f533c05396dae8`;
the connection-liveness assertion is in final candidate `13e9d176ac99897c393fb2ee74248340c292136b`.
The targeted command
`mvnw.cmd -B -ntp test-compile -Dit.test=DirectoryCapacityReferralScenarioIT#volumeTwoHappyPathRunsThroughDirectoryCapacityReferralMatchingAndTransfer failsafe:integration-test failsafe:verify`
passed at exact candidate `13e9d17`: 1 integration test, 0 failures, 0 errors, 0 skips; elapsed
5:14. It emitted versioned `PHASE13_PROFILE_BENCHMARK` records for both profiles. Earlier executions
of the mixed operation mix passed as well; latency varied, so results are characterization rather
than a capacity target.

The harness uses a fixed 20-operation cycle, two warm-up cycles, five measured cycles per profile,
four concurrent workers, and a deterministic round-robin operation/replica assignment. Each profile
therefore times 100 operations. It has no randomized operation selection and requires no random
seed. Workload setup and warm-up are outside the measured interval. The timed cycle is:

| Operation | Per measured cycle | Share | Measured operations/profile |
|---|---:|---:|---:|
| Directory facility lookup | 4 | 20% | 20 |
| Capacity readiness evaluation | 3 | 15% | 15 |
| Referral read | 2 | 10% | 10 |
| Matching recommendation read | 1 | 5% | 5 |
| Matching recommendation evaluation | 1 | 5% | 5 |
| Transfer read | 2 | 10% | 10 |
| Transport request read | 2 | 10% | 10 |
| Scoped notification/SSE replay | 2 | 10% | 10 |
| Local federated receiving-operation replay | 1 | 5% | 5 |
| Analytics aggregate computation | 1 | 5% | 5 |
| Local mock aggregate export | 1 | 5% | 5 |
| **Total** | **20** | **100%** | **100** |

All dimensions below are non-zero and asserted by the test. Counts are per profile unless noted.

| Workload dimension | Synthetic fixture / available state | Operations across warm-up + measurement | Operations measured |
|---|---:|---:|---:|
| Organizations / facilities | 2 / 5 | Directory lookups: 28 | 20 |
| Capacity observations | 3 | Readiness evaluations: 21 | 15 |
| Referrals | 2 | Referral reads: 14 | 10 |
| Matching requests/evaluations | 1 recommendation | 7 reads + 7 evaluations | 5 reads + 5 evaluations |
| Transfers | 1 | Transfer reads: 14 | 10 |
| Transport workflow records | 1 | Transport reads: 14 | 10 |
| Projected notification events / scoped SSE identities | 1 / 14 | Replay queries: 14 | 10 |
| Simultaneously active authorized SSE connections | 2 | Continuously checked before/after each cycle | Minimum observed: 2 |
| Federation peers / receiving operations | 1 / 1 | Receiving-operation replays: 7 | 5 |
| Analytics indicators / periods / seeded snapshots | 1 / 14 / 14 | Computations: 7, resulting snapshots: 7 | Computations: 5, resulting snapshots: 5 |
| Local mock export operations | 1 mock | Exports: 7 | 5 |

The local mock receives all 14 exports across both profiles. The test asserts that Analytics
computations complete, federation replay returns the same receiving request identity, and exactly
one receiver receipt exists for the stable operation identity.

Analytics computation and local mock export have application service-boundary implementations but
no packaged HTTP operation in the frozen application contract. The harness invokes those service
boundaries from the benchmark Spring/JUnit process and reports its CPU, heap and Hikari pool
separately. All other timed classes use the packaged application HTTP process(es). This process
boundary is included in the measurements and is not represented as work performed by the packaged
replica(s).

### Environment and pinned-revision measurements

The local acceptance environment was Windows 11, Temurin Java `25.0.4.1+1-LTS`, PostgreSQL 18.6,
Docker Desktop 29.3.1, 8 reported processors, and a Docker runtime reporting 5,930 MiB. The database
was disposable `postgis/postgis:18-3.6` at image digest
`sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677`. Both profiles used the
same synthetic database fixture and the same measured mix.

| Metric | Profile A — single packaged application process | Profile B — two packaged application processes |
|---|---:|---:|
| App process IDs / HTTP ports | `40276` / `49679` | `32260`, `16088` / `51374`, `51375` |
| Measured operations; failures; timeouts | `100; 0; 0` | `100; 0; 0` |
| Simultaneously active SSE connections during timed mix; minimum observed | `2; 2` | `2; 2` (one per replica) |
| Measured duration | `3.397 s` | `5.342 s` |
| Overall p50 / p95 / p99 | `65.2634 / 415.5709 / 615.7802 ms` | `119.6138 / 541.1526 / 909.2199 ms` |
| Throughput | `29.4400 operations/s` | `18.7207 operations/s` |
| Packaged app CPU time / core-equivalent | `5.797 s / 1.707` | `8.625 s / 1.615` (sum across two JVMs) |
| Packaged app peak JVM heap | `638,499,408 bytes` | `1,227,213,224 bytes` (sum across two JVMs) |
| Benchmark harness CPU time / core-equivalent | `2.469 s / 0.727` | `1.609 s / 0.301` |
| Benchmark harness peak JVM heap | `581,435,392 bytes` | `626,524,160 bytes` |
| App active DB connections, sampled peak / pool capacity | `6 / 10` | `8 / 20` |
| Harness active DB connections, sampled peak / pool capacity | `2 / 10` | `1 / 10` |
| Combined active DB connections, sampled peak / combined capacity | `8 / 20` | `9 / 30` |
| PostgreSQL statement calls during measured interval | `2,870` | `3,047` |
| Oldest pending outbox age at interval end | `40,998 ms` | `144,042 ms` |
| Age of newest notification projection at interval end | `41,437 ms` | `144,483 ms` |
| Pending/failed Analytics computations at interval end | `0 / 0` | `0 / 0` |
| Unresolved exports / oldest unresolved export age | `0 / 0 ms` | `0 / 0 ms` |

CPU and heap values for packaged application JVMs exclude the benchmark harness. DB pool peaks are
sampled every 300 ms; JVM heap is sampled every 200 ms. The outbox age is the oldest row in the
pending/leased/stalled/unhandled delivery states. The notification value is projection freshness
(age of the most recently projected record), not a source-to-projection latency claim. Analytics
backlog includes pending, processing and retry-pending computation operations; permanent failures
are recorded separately. The following SHA-256 values fingerprint simplified PostgreSQL `EXPLAIN
(FORMAT JSON)` probes against the principal indexed relations used by the paths. They are useful
relation-level plan fingerprints, not plans captured from the complete application statements (for
example, the Capacity projection query also joins correction evidence and orders by observation
time):

| Path | Fingerprint |
|---|---|
| Directory facility-key filter probe | `e0342631c3c454514fe4be1e6e70c7ec70b6cb5fb955d8b00f30fc1fb8d4f940` |
| Capacity facility/dimension observation selector probe | `86276d5aba7073ed8cf2480d08aec1e04987657c37e755ba9dd5dc87e60053cc` |
| Notification projection scope/page probe | `00bcdd7cec9432a0eb550ebaee1ed7f0e7dfb9c1587ca83050a715b57e9528f4` |

Profile B's measured throughput was slightly lower than Profile A in this local small synthetic
scenario; its p95 was lower and p99 higher on this run. Neither result is converted into a
production capacity, availability or scaling claim. Earlier runs of the same workload mix also
completed with zero errors/timeouts, while p50/p95/p99 and throughput varied across runs. These are
finite characterization samples, not a performance SLO or Phase 14 optimization exercise.

### Canonical verification and unchanged executable artifact

The canonical Java 25 verification at `c9bc4a281d1702288ad3dffbd77d491155b24ddb` is preserved as
PASS as supplied for this continuation: Surefire 489/489, Failsafe 420/420, 0 failures, 0 errors,
0 skips, Maven exit 0, Temurin `25.0.4.1+1`, Maven `3.10.0`, Spring Boot `4.1.1`, and 93 Flyway
migrations. The authoritative PASS summary did not include total elapsed time. No new full
`clean verify` was run. The earlier `AVAILABLE` to `INVALID` IPS fixture failure was addressed by the
readiness fix in `5692029` and the corrected synthetic mCSD searchset fixture in `c9bc4a2`; the
affected integration class then passed two targeted 9/9 reruns. Frozen Volume II/III semantics were
not weakened. Changes after the canonical build are confined to test sources. At final benchmark
candidate `13e9d17`, the application JAR SHA-256 is
`34854DD41248A22E4B29A65EC9AD9C9F055682CC53F088DB98DD9D8ED705F0B8`, unchanged from the already
accepted artifact; artifact-bound SBOM, vulnerability, provenance and local signature evidence
therefore remains bound to the same JAR.

### Retention disposition reconciliation

The earlier owner-wide retention/restore **MAJOR** is closed as stale. The frozen requirement is
met by the 16-category owner register and the explicit separation between executable action,
owner-provided validity/expiry semantics, configuration/evidence-only lifecycle, and history or
unresolved-state preservation. Acceptance exercises the executable Platform outbox/notification
and Referral restrictions/holds, Capacity expiry-to-stale semantics, and the expanded restore
fixture with owner reapplication before readiness. The other categories are expressly classified
without claiming a generic purge/minimization adapter. This is not a claim of universal deletion,
legal retention approval, or physical PatientReference minimization. No retention duration or legal
basis was invented. See `docs/development/production-retention-register.md` for the 16 owner rows
and the precise behavior and evidence for each.

### Final acceptance gate status

| Gate | Result | Evidence / limit |
|---|---|---|
| Canonical Java 25 clean verification | PASS | Preserved result at `c9bc4a2`; no full rerun per instruction. |
| Representative Profile A workload | PASS | Candidate `13e9d17`; one packaged process; 100 measured mixed operations; two live SSE streams continuously verified; 0 failures/timeouts. |
| Representative Profile B workload | PASS | Candidate `13e9d17`; two packaged JVMs sharing disposable PostgreSQL; 100 measured mixed operations; two live SSE streams continuously verified; 0 failures/timeouts. |
| Expanded backup/restore fixture | PASS | Earlier accepted local synthetic restore evidence retained; no rebuild required for this test-only benchmark change. |
| Two-process worker/failover acceptance | PASS | Earlier accepted packaged-process acceptance retained; benchmark additionally proves two-profile workload processes. |
| PostgreSQL-shared request budgets | PASS | Earlier accepted scoped/cross-replica budget tests retained. |
| Reviewed vulnerability inventory | PASS | Earlier accepted local OSV/CISA inventory remains digest-bound. |
| SPDX 3.0.1, provenance and local signature | PASS | Existing evidence remains bound to the unchanged JAR digest. |
| Owner retention classification and executable-action tests | PASS | The stale owner-wide MAJOR is closed for the reasons above; unsupported deletion behavior remains expressly out of scope. |
| Independent final review | PASS | Final review against the canonical baseline found 0 BLOCKER and 0 MAJOR. One test-harness maintainability MINOR remains non-blocking; the query-plan labeling MINOR was resolved in `489d474`. |
| Phase 14 implementation | NOT STARTED | No Phase 14 work was performed. |

### Independent final code review

The independent review covered the benchmark and acceptance changes from canonical PASS baseline
`c9bc4a281d1702288ad3dffbd77d491155b24ddb` through final documentation clarification
`489d474c93523891802c448e9f111deea9a78e3e`.

| Axis | BLOCKER | MAJOR | MINOR | DOCUMENTATION | Disposition |
|---|---:|---:|---:|---:|---|
| Standards | 0 | 0 | 1 | 0 | The test-only benchmark helper combines packaged-process lifecycle, HTTP/SSE handling, JMX, database instrumentation, percentile calculations and reporting. This is a maintainability judgement call; no correctness or safety issue was found. |
| Specification | 0 | 0 | 0 unresolved | 0 | A minor query-plan wording issue was resolved: the recorded hashes are explicitly identified as simplified relation-level EXPLAIN probes, not full application SQL plans. |

The spec review confirmed the measured Profile A uses one packaged JVM and Profile B uses two
packaged JVMs on separate ports against the same disposable PostgreSQL database. The timed mix
exercises all declared contexts, maintains two live authorized SSE connections, reports its process
and database measurements, and makes no production-capacity claim. Analytics and local mock export
use documented service-boundary calls from the benchmark harness JVM with separate CPU, heap and
pool measurements. No frozen business semantics changed, no external system was used, and no Phase
14 scope was introduced.

All mandatory Phase 13 gates are satisfied. The Phase 13 freeze and separate program-state
governance follow-up are recorded above; Phase 14 remains not started.

### Retro

The repository CI already runs the canonical Java 25 `clean verify`, and the acceptance record
preserves the focused benchmark command and both profile outputs. No new build guardrail was needed.
The remaining maintainability follow-up is to consider factoring the test-only benchmark helper's
process lifecycle, HTTP/SSE handling, JMX/database sampling and result reporting into smaller
helpers. That is non-blocking and was left unchanged so the accepted characterization and scope
remain stable. No Phase 14 work was started.
