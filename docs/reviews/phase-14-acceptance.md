# Phase 14 Acceptance Record

**Disposition:** IN PROGRESS — Phase 14 and Volume V are not frozen. No release has been published.
**Branch:** `codex/phase-14-volume-v-closeout`.
**Phase 13 checkpoint:** sanitized local `phase-13-freeze` tag is present on GitHub; this record does not move or replace that tag.
**Data policy:** local, unmistakably synthetic fixtures only. No external healthcare system or model provider was used.

This record separates completed evidence from open gates. A green UI or focused test does not substitute for a failed canonical backend verification.

## Completed product-path evidence

| Gate | Result | Evidence and limit |
|---|---|---|
| Approved productization addendum and local P14 ticket graph | PASS | `docs/specs/phase-14-productization-addendum.md`; local issues under `.scratch/phase-14-volume-v/issues/`. No external issue tracker was used. |
| Synthetic Demo business-data path | PASS | `ui/e2e/synthetic-demo-data.spec.ts` passed against the loopback Demo stack. It observed seeded Directory, Capacity, Referral, Matching, Transfer, Transport, Analytics and Operator Support states through the UI. The stack used synthetic records only. |
| External provider profile UX and fail-closed evaluation gate | PASS | `ui/e2e/external-provider-profile.spec.ts` passed. The exact external profile appeared as paused pending review; the advisory action remained disabled. No provider request was sent. |
| Demo manifest and launcher safety | PASS | `Synthetic-Demo.Tests.ps1` and `Start-AyuVahak.Tests.ps1`: 10 passed, 0 failed. They validate synthetic identity/owner scope, versioned seed data, explicit mode choice and local port/OIDC behavior. |
| Frontend static checks | PASS (previous recorded run) | Node 24.21.0 strict typecheck, Biome lint, Vitest 17 files / 97 tests and Vite production build passed. This is the prior local result; rerun only if frontend source changes. |
| Clean Empty mode and deterministic local provider paths | PASS (previous recorded run) | Prior clean-install/E2E evidence is retained. The current stopped local Demo installation is not reused as evidence for persona coverage. |

The current Keycloak source realm contains the eight fictional Demo identities. The earlier stopped package was created before the role-specific identities were added and contains only two identities. Therefore the role-specific browser/API isolation E2E remains open against a fresh package.

## Canonical backend verification

The most recent `clean verify` on the current Phase 14 source **failed** and is not freeze evidence. It used Temurin Java 25.0.4.1+1, Maven 3.10.0, Spring Boot 4.1.1, and Flyway 93 migrations. Surefire passed 558 tests with no failures, errors, or skips. Failsafe ran 423 tests and recorded 3 failures and 2 errors, with no skips; Maven exited nonzero.

The observed failure categories were a Keycloak discovery deadline during cold local realm import, a packaged replica process that did not reach liveness within its former 90-second bound, and load-sensitive Transfer/Referral integration failures. The measured app process start under suite load was 338 seconds; the Keycloak import exceeded its former five-minute deadline. Test-only bounds were adjusted to 8 minutes and 10 minutes respectively, retaining the same liveness and HTTP discovery assertions. The replica packaged-JAR command was formatted to satisfy Spotless; `spotless:check` subsequently passed. Transfer cancellation recovery and the Referral snapshot class passed focused isolated reruns, so no owner semantics, transaction timeout, or assertions were weakened.

These test-harness changes have not yet been validated by a subsequent canonical run. The current canonical gate remains **OPEN / FAIL** until `clean verify` passes on the finalized candidate.

## Product and release gates still open

| Gate | Status | Required closure |
|---|---|---|
| Role-specific Demo browser and direct API scope E2E | OPEN | Run `demo-persona-access.spec.ts` with the current eight-user realm and fresh seed-state against a loopback clean package. |
| Canonical Java 25 clean verification | OPEN / last run FAIL | Run one final `.\mvnw.cmd -B -ntp clean verify` after code/test changes are settled. Record actual reports, elapsed time and exit status. |
| Independent Phase 14 code review | OPEN | Run the two-axis review against the Phase 13 freeze after implementation changes are committed; resolve BLOCKER/MAJOR findings. |
| Final package and clean-user launch | OPEN | Generate the v0.1.0 local bundle from the exact accepted JAR/UI; extract in a separate temporary directory and verify Demo/Empty choice and startup. |
| Screenshots from actual Synthetic Demo UI | OPEN | Capture genuine local Synthetic Demo screens after the final package is running; do not fabricate or use real data. |
| Final release SBOM, local vulnerability review, provenance, checksums and signature evidence | OPEN | Bind evidence to the exact backend/frontend distribution artifact; do not publish until all local checks pass. |
| Phase 14 freeze and Volume V integrated closeout | OPEN | Require all applicable Phase 14 gates, final independent review with zero BLOCKER/MAJOR, and truthful acceptance records. |
| GitHub main integration, SemVer tag and release publication | NOT STARTED | Re-fetch and inspect the remote immediately before normal integration; preserve the immutable Phase 13 tag and never force-push. |

## Safety and scope

- Phase 13 remains complete and frozen; its sanitized checkpoint tag is unchanged.
- Demo and E2E data use fictional `SYN-*` identifiers. No real PHI or production credential is used.
- No public/shared FHIR, DHIS2, healthcare, federation, cloud, registry, or external model target was used.
- Operator Support is disabled by default, finite-topic, source-cited, advisory and unable to mutate workflow state. The external-profile test does not make an external call.
- Patients are not direct users. No patient portal or clinical AI is in scope.
- The repository has no `LICENSE`; no license or reuse permission is claimed.
- Volume V remains incomplete until the final acceptance, freeze, and publication gates are individually evidenced.
