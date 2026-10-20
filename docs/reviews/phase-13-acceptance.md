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
| P13-03 HTTP and abuse controls | Implemented | Local management, HTTP, CORS, and rate-limit controls/tests; limits remain application/deployment configuration. |
| P13-04 SLI/SLO and capacity | Implemented, deployment evidence partial | Bounded SLI catalogue and configurable declarations exist; no universal SLO or external monitoring operation is claimed. |
| P13-05 Migration recovery | Implemented | Controlled migration/readiness behavior and PostgreSQL migration integration tests; production upgrade evidence remains deployment-specific. |
| P13-06 Replica/worker/shutdown | Partial | Bounded shutdown and duplicate PostgreSQL outbox-worker claim tests exist. A two-process/two-application-replica acceptance run is not yet evidenced. |
| P13-07 Retention | Partial | Owner-scoped Platform outbox purge and Referral identifier restriction/reapplication are implemented. Other matrix owners do not yet have complete purge/minimization adapters or restore replayers. |
| P13-08 Backup/restore | Local acceptance passed | Isolated synthetic `pg_dump`/`pg_restore`, Flyway, restored application, retention reapplication, and unresolved Analytics export barrier are exercised. The post-backup Directory update is intentionally lost at the dump recovery point. |
| P13-09 Release evidence | Partial / BLOCKED | Local SemVer, provenance, ephemeral signature, tamper verification, and vulnerability review tooling has 10 passing Python tests. Frozen SPDX 3.0 generation and schema/profile validation are not yet present. |
| P13-10 Operations/DPG evidence | Partial | Local runbooks, ASVS 5.0.0 matrix, standards register, and evidence-based readiness matrix are present; governance and live deployment evidence remain open. |
| P13-11 Integrated acceptance | Not complete | Full clean verify, complete retention coverage, true multi-replica acceptance, complete SPDX 3.0 evidence, and independent review/freeze remain outstanding. |

## Backup and restore acceptance

The acceptance uses two local PostgreSQL/PostGIS 18.6 containers with synthetic fixtures only. The
target is recreated from `template0`, restored from a custom-format `pg_dump`, and started with a
local fake JWKS endpoint. The fixture covers a grant, Directory organization, audit record, delivered
outbox event, Referral restriction, and unresolved DHIS2 export/target-tuple claim. A post-backup
retention action must be reapplied before readiness becomes healthy. A privacy marker is checked
against retention manifests, audit, event, and Analytics export operation state.

The test declares a synthetic 15-minute RPO and RTO. In the latest run, recovery took 3m21.730s
from `pg_restore` start through restore reapplication/readiness and authorized read; the
dump-completion-to-recovery window was 3m30.468s. Both were within the declared test targets. The
method is a logical snapshot; it does not exercise WAL/PITR, scheduled backups, encryption, or
off-host storage. No general production RPO/RTO claim follows from this test.

## Local verification so far

- `BackupRestoreAcceptanceIT`: latest version passed 1 Failsafe test (0 failures, 0 errors, 0
  skips; 505.4 seconds). It demonstrated post-backup business-state loss, retention reapplication
  before readiness, restored `OUTCOME_UNKNOWN` tuple barrier preservation, and an authorized read.
- `ProductionFlywayMigrationStrategyIT`: 4 integration tests passed in an earlier focused run.
- Focused retention regression suites passed earlier: Event delivery 6, outbox PostgreSQL 14, and
  Referral draft API 34.
- Local release-evidence Python tests: 10 passed, 0 failures/errors/skips in 60.5 seconds.
- The canonical `clean verify` and full Volume I–IV regression have not yet passed in this run.

## Readiness and DPG evidence

The production readiness matrix currently reports 0 READY, 18 PARTIAL, 1 MISSING, and 2
DEPLOYMENT-SPECIFIC areas. Local implementation changes improve evidence but do not establish a
production environment's operation, support, or legal configuration.

The DPG matrix remains evidence-based. It does not assert registration, certification, legal
compliance, project ownership, or licensing. Governance evidence and the license decision remain
unresolved; no nomination or submission was made.

## Review and freeze gate

The independent Phase 13 review is pending. At least the following freeze conditions remain unmet:

1. The frozen release-evidence gate requires generated and validated SPDX 3.0; current tooling
   explicitly fails release eligibility without it.
2. Owner retention coverage is incomplete outside Platform outbox and Referral restrictions.
3. Two-application-replica acceptance and the full integrated Phase 13 gate are not yet evidenced.
4. Canonical full clean verification and the final review have not completed.

No Phase 13 freeze commit or governance update to “complete and frozen” is authorized while these
conditions remain unresolved. Phase 14 remains specification-frozen and implementation-not-started.

## Safety and scope

All acceptance targets are local and synthetic. No public healthcare/DHIS2/FHIR system, cloud
resource, external vulnerability target, public registry, production secret, real health data, DPG
portal, or remote Git write was used. No Phase 14 code, dependencies, tickets, or optimization work
was added.
