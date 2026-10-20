# Phase 13 Acceptance Record

**Status:** INCOMPLETE — Phase 13 is not frozen and Phase 14 has not started.
**Assessment date:** 2026-10-20.
**Acceptance target:** local-only, synthetic data, frozen Phase 13 specification.

## Approved ticket graph

The human-approved 11-slice graph is stored locally under
`.scratch/phase-13-production-hardening-dpg-readiness/issues/`. It covers portable production
profiles, provider-neutral secrets, HTTP/abuse controls, SLI/SLO and capacity evidence, migration
recovery, replica/worker/shutdown behavior, owner retention, backup/restore, release evidence,
operator/DPG documentation, and integrated acceptance/freeze. No external tracker was used.

## Implemented evidence

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
| P13-11 Integrated acceptance | Not complete | A prior canonical clean verification passed, and local SPDX 3.0.1 generation/validation now passes. The current canonical verification attempt failed before Maven startup because `JAVA_HOME` points to an inaccessible JDK 25 path. Complete retention coverage, true multi-replica acceptance, measured deployment-profile workload baselines, vulnerability inventory review, and blocker remediation remain outstanding. |

## Backup and restore acceptance

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
`689e0f8` could not start because the configured `JAVA_HOME` points to an inaccessible JDK 25 path;
these values are prior-run evidence, not a verification of that attempt. The method is a logical
snapshot; it does not exercise WAL/PITR, scheduled backups, encryption, or off-host storage. No general production
RPO/RTO claim follows from this test.

## Local verification so far

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
- The canonical full clean verification passed before the latest local Python/doc-only release-evidence changes. Its repeat at `689e0f8` exited before the Maven build with `JAVA_HOME` pointing to an inaccessible JDK 25 location; JDK 17 is available but cannot compile the Java 25 project. No JDK was fetched. The frozen integrated Phase 13 acceptance gate remains incomplete because owner retention/recovery coverage, two-replica acceptance, measured workload evidence, and vulnerability-inventory review are still missing.

## Readiness and DPG evidence

The production readiness matrix currently reports 0 READY, 18 PARTIAL, 1 MISSING, and 2
DEPLOYMENT-SPECIFIC areas. Local implementation changes improve evidence but do not establish a
production environment's operation, support, or legal configuration.

The DPG matrix remains evidence-based. It does not assert registration, certification, legal
compliance, project ownership, or licensing. Governance evidence and the license decision remain
unresolved; no nomination or submission was made.

## Review and freeze gate

The independent Phase 13 reviews found unresolved findings. Their disposition is:

| Severity | Finding | Resolution |
|---|---|---|
| BLOCKER | Owner retention/restore coverage is incomplete outside Platform outbox and Referral restrictions; the frozen owner-by-owner purge/minimization, hold, and restore-reapplication evidence is not complete. | Unresolved. Do not freeze. |
| BLOCKER | Two-application-replica acceptance is not evidenced; worker-claim tests do not prove the frozen multi-replica scenario. | Unresolved. Do not freeze. |
| BLOCKER | No measured synthetic workload baseline is recorded for either supported deployment profile. | Unresolved. Do not freeze. |
| MAJOR | Rate limits lack required tenant/scope, peer/partner, and operation budgets; per-process buckets can multiply across replicas. | Unresolved. Do not freeze. |
| MAJOR | The restore fixture omits required idempotency and representative Transfer, Transport, and federation invariants. | Unresolved. Do not freeze. |
| MAJOR | No reviewed dependency vulnerability inventory is bound to the release manifest; release eligibility remains false. | Unresolved. Do not freeze. |
| MINOR | Independent standards review noted possible divergent-change smells in the combined release-evidence module and large production-profile binding class. | No correctness defect identified; retain as maintainability follow-up. |
| DOCUMENTATION | Earlier readiness text used stale baseline/release claims and inconsistent restore figures. | Corrected in the local documentation follow-up commit. |

At least the following freeze conditions remain unmet:

1. SPDX generation, provenance, local ephemeral signing, manifest binding and signed bundle
   verification now pass for local synthetic acceptance. A reviewed dependency vulnerability
   inventory is not bound to that manifest; release eligibility correctly remains false.
2. Owner retention coverage is incomplete outside Platform outbox and Referral restrictions; the
   frozen owner-by-owner purge/minimization, hold, and restore-reapplication evidence is not complete.
3. Two-application-replica acceptance is not evidenced; worker claim tests do not prove the frozen
   two-replica acceptance scenario.
4. The required measured synthetic workload baseline has not yet been recorded for either supported
   deployment profile.
5. The repeat full clean verification at `689e0f8` could not start because `JAVA_HOME` points to an
   inaccessible JDK 25 installation. The previous clean verification is historical evidence only;
   the current verification attempt is not a pass.

6. Rate-limit dimensions, the broader restore fixture, and a reviewed vulnerability inventory remain
   incomplete as classified above.

No Phase 13 freeze commit or governance update to “complete and frozen” is authorized while these
conditions remain unresolved. Phase 14 remains specification-frozen and implementation-not-started.

## Safety and scope

All acceptance targets are local and synthetic. No public healthcare/DHIS2/FHIR system, cloud
resource, external vulnerability target, public registry, production secret, real health data, DPG
portal, or remote Git write was used. No Phase 14 code, dependencies, tickets, or optimization work
was added.
