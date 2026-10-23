# Phase 14 Acceptance Record

**Disposition:** PHASE 14 PASS — accepted and frozen by the Phase 14 freeze commit in Git history. Integrated local Volume V acceptance also passes. GitHub `main` integration and the annotated `v0.1.0` tag are verified; release asset publication and downloaded-asset smoke remain pending.
**Branch:** `codex/phase-14-volume-v-closeout`.
**Phase 13 baseline:** Complete and frozen at `57b949ab8c5fa28e95243e83df0910731dbec800`; the sanitized `phase-13-freeze` checkpoint remains in its ancestry and on GitHub.
**Candidate:** `cd75fb24956a6f79572abe9d68acaab73e6ae69e` at the time of the package acceptance runs below.
**Release source commit/tag:** `fd1b4720e8bdef558a4376f2bb2c49ec5dc9ebd9` (`v0.1.0`). The release candidate provenance and annotated tag resolve to this commit. The remote `main` and closeout branch now include the documentation-only checkpoint `f42a52e2cb4ccff78bae5132fdfa740e58447c62`; the repository default branch still requires an authenticated GitHub settings session.
**Data policy:** Local, unmistakably synthetic fixtures only. No external healthcare system or model provider was used.

This record preserves historical failures as history and reports the latest evidence separately. Focused frontend/package checks supplement the canonical Java verification; they do not replace it.

## Chronology and current disposition

| Stage | Evidence | Disposition |
|---|---|---|
| Initial Phase 14 development | Earlier browser/session failures, stale Demo manifest findings, and incomplete package/release evidence are retained in commit history and prior dated notes. | Historical; not current blockers. |
| Product-path remediation | Owner-API Synthetic Demo data, capability-scoped workspaces, an Empty first-run path, and the external-provider review gate were implemented and exercised. | Accepted by the fresh-package E2Es below. |
| Application build baseline | `7bb5ea2130134b642e48cb363030d9baf338ba6c`; Temurin 25.0.4.1+1, Maven 3.10.0, Spring Boot 4.1.1, 93 Flyway migrations; Surefire 561 and Failsafe 423, with 0 failures, errors, or skips; Maven exit 0. | Canonical Java verification PASS. |
| Package and release-evidence candidate | Candidate `cd75fb24956a6f79572abe9d68acaab73e6ae69e`; changes after the application baseline are release-evidence tooling and documentation only. The packaged application JAR SHA-256 is `A723686F51E349DB0FD91100A3AB9C4171D037E53C66101CBDD499048E60075C`. | JAR is byte-bound to package and evidence; no backend rebuild is indicated by the helper/docs-only changes. |
| Current remaining gates | Final independent review, acceptance-record reconciliation, freeze, Volume V integrated closeout, and normal GitHub integration/release. | Open; no freeze or publication claim is made yet. |

## Product and clean-package acceptance

All browser runs used a ZIP extracted into a separate temporary directory, loopback services, local PostgreSQL/PostGIS and Keycloak, and synthetic records. Playwright configuration and diagnostic instrumentation were temporary and removed after the runs.

| Gate | Result | Evidence and limits |
|---|---|---|
| Synthetic Demo business-data path | PASS | `ui/e2e/synthetic-demo-data.spec.ts`, 1/1, on the extracted release candidate. The UI exercised Demo Overview, Directory, Capacity evidence, Referrals, Matching, Transfers, Transport, aggregate Analytics, and disabled Operator Support. The seed was explicit, versioned, synthetic, and used owner-approved APIs. |
| Persona and scope authorization | PASS | `ui/e2e/demo-persona-access.spec.ts`, 1/1, seven synthetic persona identities. It checked capability-based navigation, denied direct API access, and cross-organization scope denial. Persona identities are Demo-only and are not production accounts. |
| Deterministic local Operator Support | PASS | `ui/e2e/operator-support-local-fake.spec.ts`, 1/1. The local deterministic provider returned a bounded, validated, source-cited advisory response for a finite topic; the UI showed the advisory/no-state-change semantics. No workflow mutation port exists. |
| External provider profile safety seam | PASS | `ui/e2e/external-provider-profile.spec.ts`, 1/1. The administrator-configured profile was visible without endpoint/secret readback; its secret reference was synthetic and unresolved; evaluation stayed disabled pending exact-profile review; the browser asserted no provider request. No external API key or real provider was used. This validates configuration/pause UX and non-egress in this path; it is not a claim that the profile has been activated or externally connected. |
| Clean Empty first run and onboarding | PASS | `ui/e2e/empty-onboarding.spec.ts`, 1/1, against the ZIP extracted into a second clean temporary state and separate loopback Compose project. Startup seeded no Demo business data. After the existing one-shot grant bootstrap, a user-created synthetic organization and facility were configured. Inventory after onboarding: 1 organization, 1 facility, and 0 Capacity observations, Referral drafts, Transfers, Transport requests, Analytics indicators/snapshots/exports. |
| Empty unauthorized identity/deep-link behavior | PASS | `ui/e2e/authenticated-empty.spec.ts`, 1/1. The initial grant without Directory authority was denied by the API and did not gain the protected workspace through a direct route. |
| Frontend static and unit checks | PASS | Node 24.21.0 / npm 11.19.0; lockfile install, strict TypeScript typecheck, Biome lint over 56 files, Vitest 17 files / 103 tests, and Vite production build passed. |
| Local PowerShell launcher/seed safety tests | PASS | `Synthetic-Demo.Tests.ps1` and `Start-AyuVahak.Tests.ps1`: 10 passed, 0 failed. These cover explicit mode selection, synthetic manifest/owner scope, and local launcher settings. |
| Screenshots | PASS | README images under `docs/assets/screenshots/` are genuine screenshots of the local synthetic UI. The overview includes the visible Synthetic Demo safety banner. No generated/fabricated product screen is represented as running UI. |
| Independent two-axis review | PASS | Review of `57b949ab8c5fa28e95243e83df0910731dbec800...cd75fb24956a6f79572abe9d68acaab73e6ae69e` found 0 BLOCKER and 0 MAJOR on both Spec and Standards axes. Spec: no implementation blocker or scope creep; provider adapter placement is corrected; package provenance matches the exact clean package. Standards: no hard documented-standard breach. One judgment-call MINOR notes the repeated `isRecord` guard in API clients; it is non-blocking and does not weaken trust-boundary schema checks. The uncommitted acceptance-record update was not part of that diff. |

The two package E2E environments were stopped after acceptance; their persistent temporary volumes were retained. Temporary generated identity/password files, temporary Playwright configuration, and failed-run browser traces were removed. No credentials are recorded in this report.

## Product boundary and safety

- The browser is a presentation client; APIs enforce grants and scope. Role templates are convenience presets over the existing authorization model.
- Synthetic Demo business data is versioned, visibly marked `SYNTHETIC DEMONSTRATION ENVIRONMENT — NO REAL PATIENT DATA — NOT FOR CLINICAL USE`, and seeded explicitly. Empty startup has no synthetic business fixtures.
- Referral, Matching, Transfer, Transport, Acceptance, Reservation, Arrival, Handover, Capability, Capacity, Availability, and Readiness remain distinct owner concepts.
- Patients are beneficiaries, not direct users. There is no patient portal or clinical AI.
- Operator Support is disabled by default, finite-topic, documentation-only, advisory, source-cited, and unable to alter coordination state. The external profile is administrative configuration; callers cannot select arbitrary URLs or content. No production provider profile was activated.
- No public/shared FHIR, DHIS2, healthcare, federation, cloud, external model, or external vulnerability target was used. No real patient data, PHI/PII, production credentials, external model call, or DPG submission was used.
- No root `LICENSE` exists. Licensing remains a repository-owner governance decision; no reuse rights or open-source license is claimed.

## Canonical backend verification

### Historical failed canonical runs

Earlier failures and an interrupted run remain historical engineering evidence. The previously failed run had zero Surefire failures but Failsafe failures/errors related to cold local Keycloak discovery, packaged replica startup under suite load, and load-sensitive integration behavior. A later run was manually interrupted and is treated as no result. The accepted run below supersedes those outcomes without deleting that history.

### Final executable-source canonical run — PASS

At `7bb5ea2130134b642e48cb363030d9baf338ba6c`, `.\mvnw.cmd -B -ntp clean verify` passed with Temurin Java 25.0.4.1+1, Maven 3.10.0, Spring Boot 4.1.1, and 93 Flyway migrations. Surefire: 561 tests, 0 failures/errors/skips. Failsafe: 423 tests, 0 failures/errors/skips. Exit 0. The accepted packaged JAR SHA-256 is `A723686F51E349DB0FD91100A3AB9C4171D037E53C66101CBDD499048E60075C`. Later changes through candidate `cd75fb2` are frontend-independent release-evidence helper and documentation changes; the packaged JAR hash was checked and remains the one recorded above.

## Optimization and intelligence

- Deterministic optimization disposition: **NO PRODUCTION OPTIMIZATION JUSTIFIED BY CURRENT EVIDENCE.** The approved synthetic golden semantic corpus and equivalence checks are present; no production optimization was introduced absent a measured bottleneck.
- Operator Support is the only initial intelligence capability. It is disabled by default and accepts finite topic keys and an allowlisted documentation corpus; it does not accept patient/case content, arbitrary user prompts, tools, or actions.
- The local deterministic fake is the only provider used in acceptance. Provider output is schema/source validated; unsupported or invalid output abstains or is unavailable. Human close/ignore does not change workflow state.
- External provider configuration was tested only in paused, unresolved-secret, no-egress state. Actual external provider connectivity remains unexercised and requires future explicit deployment review/configuration; no external inference service is required for the product.
- The external profile gate includes an administrator-controlled exact-profile fingerprint/review seam. The external profile is not active solely because it is configured.

## Final local release candidate

The package tested by the complete Demo/Empty/persona/operator-support browser suite was generated from the same application JAR, frontend tree, and local runtime files. The final publication ZIP was regenerated from release source commit `fd1b4720e8bdef558a4376f2bb2c49ec5dc9ebd9` after the product-entry documentation update. It contains the same runtime payload, with updated source provenance and release manifest. The exact final ZIP still requires the post-publication download/startup smoke below.

| Evidence | Value / disposition |
|---|---|
| ZIP SHA-256 | `FF4C383DBCDC0828D8DE8FCF76E4D5D0A21413095C441DBC53B25076E5251DF4` |
| Application JAR SHA-256 | `A723686F51E349DB0FD91100A3AB9C4171D037E53C66101CBDD499048E60075C` |
| SPDX | SPDX 3.0.1; validation PASS; 325 package nodes / 324 runtime dependency entries. SHA-256 `21d38b9f7fe074d1745046136e1588017d263bd161be47e338a2bb9e8cb70550`. |
| Local dependency review | OSV Scanner 2.7.0 strict local/offline match; 324 dependency occurrences, 0 findings in the retrieved snapshots. The final evidence manifest re-hashes and verifies the OSV/Maven/npm and CISA KEV snapshot, raw scan result, CycloneDX scan input, release SPDX, and JAR bindings. Snapshot manifest SHA-256 `f5c5dd27ab8250282e9008403eddae72ad1f3fb3d7abdecd55b38f5dc7b3b9bb`; retrieved 2026-10-23. A zero-finding result is not a claim of vulnerability-free software. Inventory SHA-256 `e8aeeb30ea2f4223febe2074f4711b57c5ed0e0be492652fedcce8b5270a2882`. |
| Provenance | Provenance SHA-256 `75470b6b609ead407807aef95e32b3fc49afbb5b893a20123359618dd0542b4f`; source revision is `fd1b4720e8bdef558a4376f2bb2c49ec5dc9ebd9`; it binds the exact JAR and frontend tree. The release manifest binds the ZIP, JAR, frontend, SPDX, and verified vulnerability inventory; manifest SHA-256 `a0fb7d72aeee07d657a488519468d73cafb83bd1bb46639cce26de88e826949e`. SLSA level is not claimed. |
| Validation | SPDX validation report SHA-256 `c768f4fd092a84da341843361b6cdcfd44a25b0675b7108c761662cc0af7b4cb`; all six entries in `SHA256SUMS.txt` verified locally. The 33-entry ZIP contains no `.scratch`, `.env`, database, log, keystore, or private key entry. |
| Local signature | Acceptance-only evidence bundle verified against ephemeral signer fingerprint `db08279384f66c24ffe1e2cc8995fb7b5c5df5b3eb287691227dd82be8eff134`; bundle SHA-256 `a2c954906f0653a5823712ee6efc599ba08651cb059b04a6a7e62fac8a892493`. It is not a production signing identity and is not selected as a public trust signature; the private test keystore is excluded from all release assets. |
| Release eligibility | Manifest reports `eligible=false`: this local build does not have a trusted production signer/verified release authorization. Publishing a downloadable reference package does not change that status or imply production approval. |

The release-evidence helper was verified by 32 passing tests and 1 symlink-permission skip. The final local release candidate is ready for GitHub asset attachment. Do not publish the acceptance-only private signer or raw vulnerability database.

## Remaining gates

| Gate | Status | Closure requirement |
|---|---|---|
| Phase 14 independent two-axis review | PASS | 0 BLOCKER and 0 MAJOR on both axes; one non-blocking MINOR. |
| Phase 14 acceptance reconciliation | PASS | Final release source, ZIP, checksums, SPDX 3.0.1, provenance, and verified vulnerability inventory are recorded above. |
| Phase 14 freeze | PASS | Local freeze commit follows this acceptance record; independent review found 0 BLOCKER and 0 MAJOR. |
| Volume V integrated closeout | PASS | `docs/reviews/volume-5-final-acceptance.md` records the Phase 13 + Phase 14 boundary review and the Volume V local freeze. |
| Main integration and annotated tag | PASS | Remote `main` and closeout branch include documentation-only follow-up `f42a52e2cb4ccff78bae5132fdfa740e58447c62`; annotated `v0.1.0` tag remains on release source commit `fd1b4720e8bdef558a4376f2bb2c49ec5dc9ebd9`. Phase 13 branch/tag remain unchanged. |
| GitHub Release and published-download smoke | PENDING AUTHENTICATED SESSION | The GitHub in-app browser is signed out, and the available GitHub connector has no release-creation or asset-upload operation. Do not automate credentials. After an owner-authenticated browser session is available, create the release, attach the listed assets, download the ZIP back, verify checksums, and smoke-test Demo/Empty startup. |

This is a local acceptance record, not a DPG certification, production deployment approval, clinical-safety certification, or legal-compliance assessment.
