# Volume V Final Acceptance

**Disposition:** VOLUME V IMPLEMENTATION ACCEPTANCE PASS; local Phase 14 and Volume V freezes are recorded below. GitHub `main` integration, the annotated `v0.1.0` tag, publication of the seven release assets, and clean downloaded-package Demo/Empty smoke are verified.
**Candidate branch:** `codex/phase-14-volume-v-closeout`.
**Phase 13 freeze:** `57b949ab8c5fa28e95243e83df0910731dbec800` (sanitized checkpoint tag remains unchanged).
**Phase 14 freeze:** `9fb8085235b18e0734be1033e498b86327c61976`.
**Data policy:** Synthetic and local-only acceptance. No public healthcare service or real model provider was used.

## Integrated phase acceptance

| Area | Result | Evidence |
|---|---|---|
| Phase 13 production hardening | PASS | Frozen Phase 13 acceptance at `docs/reviews/phase-13-acceptance.md`; sanitized freeze remains in the current ancestry and on GitHub. Phase 14 made no change to the Phase 13 runtime implementation. |
| Phase 14 implementation | PASS + FROZEN | `docs/reviews/phase-14-acceptance.md`; local freeze commit `9fb8085235b18e0734be1033e498b86327c61976`. |
| Productization design | PASS | Controlled `docs/specs/phase-14-productization-addendum.md` supplements, and does not rewrite, the frozen Volume V architecture/specification. |
| Care-coordination source of truth | PASS | Browser operations use authenticated owner APIs; no frontend persistence shortcut or owner-rule rewrite was found in the Phase 13-to-Phase 14 review. |
| Authorization and scope | PASS | Seven synthetic persona identities exercised capability-driven navigation, direct API denial, and cross-organization denial in `ui/e2e/demo-persona-access.spec.ts`. Backend remains authoritative. |
| Synthetic Demo | PASS | Clean ZIP Demo run; explicit mode and seed; versioned `SYN-*` facts appeared across Directory, Capacity, Referral, Matching, Transfer, Transport, aggregate Analytics and local/mock status. Every authenticated Demo view carries the synthetic/no-patient/not-for-clinical-use warning. |
| Empty deployment | PASS | Separate clean ZIP state started without Demo data. After local one-shot access bootstrap, the operator created one synthetic organization and facility. No seeded Capacity, Referral, Transfer, Transport, Analytics, or export facts were present. |
| Optional intelligence | PASS | Disabled by default; local deterministic fake only in acceptance; finite documentation topics; approved source validation; advisory-only output; no mutation, tools, or case/patient input. External profile remains paused until exact-profile evaluation/review is recorded. |
| Deterministic optimization | PASS | `docs/reviews/phase-14-optimization-disposition.md` records **NO PRODUCTION OPTIMIZATION JUSTIFIED BY CURRENT EVIDENCE**. Versioned synthetic golden corpus and semantic equivalence tests pass; no optimization implementation or business-semantic change was introduced. |
| Downloadable local package | PASS (published and downloaded) | The published `AyuVahak-0.1.0-local.zip` was downloaded into a fresh temporary directory, checksum-verified, and exercised without Maven, Node, or a source checkout. Empty mode started with zero synthetic business rows; Synthetic Demo started and seeded the documented synthetic owner-API records. Exact counts and digests are in the Phase 14 acceptance record. |
| Backend verification | PASS | Canonical Java 25 `clean verify` at `7bb5ea2130134b642e48cb363030d9baf338ba6c`: Temurin 25.0.4.1+1; Maven 3.10.0; Spring Boot 4.1.1; 561 Surefire + 423 Failsafe; 93 migrations; zero failures/errors/skips; exit 0. The packaged JAR digest remained unchanged through the release-evidence-only commits. |
| Frontend verification | PASS | Node 24.21.0 / npm 11.19.0; `npm ci`, strict typecheck, Biome, 103 Vitest tests across 17 files, and Vite production build passed. |
| Package browser acceptance | PASS | Fresh-package Demo data 1/1; seven-persona/scope 1/1; local fake Operator Support 1/1; paused external provider profile 1/1; clean Empty onboarding 1/1; restricted Empty deep-link/API 1/1. All used loopback/local services and synthetic identities. |
| Screenshots and repository documentation | PASS | README includes genuine local Demo screenshots, installation, boundaries, architecture, standards, testing, license status, and limitations. No license is designated; no reuse permission is claimed. |
| Independent integrated review | PASS | Two-axis review of the full Phase 13-to-Phase 14 diff found 0 BLOCKER and 0 MAJOR on both axes. One non-blocking MINOR is repeated generic API response-record guards; it does not weaken local schema validation. The provider-specific wire adapter is in the adapter boundary. |
| External safety | PASS | No public healthcare/DHIS2/FHIR target, cloud, external model, registry publication, PHI/PII, production credential, DPG submission, or external security probe was used. |
| Phase 14 scope | PASS | No patient portal, clinical AI, AI mutation/tool authority, Phase 15/VI, or unauthorized later-phase implementation. |

## Retrospective

- Fresh-package acceptance found issues that source-tree browser tests could not establish: mode isolation, launcher behavior, and whether the release bundle actually contains the UI and required local services.
- Demo business data must be visibly synthetic and seeded by an explicit owner-API command. Empty-mode acceptance is strongest when it inventories the actual clean database after onboarding rather than inferring emptiness from a startup screen.
- A configured external provider is not an approved active provider. Keeping the exact-profile review fingerprint fail-closed made it possible to exercise the configuration UX while proving that acceptance emitted no external request.
- Release evidence must bind the JAR, frontend tree, SBOM, vulnerability inventory, and source revision; package provenance that covers only the backend is incomplete for a full-stack distribution.
- Re-read remote branches, tags, and default-branch metadata at integration time. The Phase 13 remote checkpoint was intact; `main` was integrated normally and is now the repository default.
- Preserve evidence chronologically: a passing targeted test, a passing canonical build, a clean-package run, a freeze, and a public downloaded-asset smoke test are distinct claims.
- Smoke-test the actual published package in separate fresh Demo and Empty installations. The downloaded Empty database stayed free of synthetic business records; Demo required the explicit bootstrap and owner-API seed steps.

## Product boundary and residual limits

The downloadable artifact is a local/reference distribution. It is not evidence of universal production readiness, legal compliance, clinical-safety certification, DPG registration, or provider quality. Institutional deployment still requires organization-specific identity, TLS, secrets, backup/restore, retention, privacy/legal review, monitoring, incident response, and capacity validation. The external provider connection has not been activated or called; the acceptance seam proves fail-closed configuration and no egress under the paused/unreviewed profile. There is no native Windows EXE: Docker Compose and its dependencies are required, so the ZIP with Windows launcher scripts is the honest supported package.

## GitHub integration and release

The remote is `https://github.com/Sumedh08/AyuVahak.git`; the authenticated GitHub identity is `Sumedh08`, and the repository API confirms the exact public repository and write permission. The sanitized Phase 13 branch/tag remain unchanged. The release source commit and annotated `v0.1.0` tag are `fd1b4720e8bdef558a4376f2bb2c49ec5dc9ebd9`. The Phase 14 closeout branch and `main` are integrated through normal pushes; no force operation was used. GitHub confirms `main` is the default branch.

The published release is [AyuVahak v0.1.0](https://github.com/Sumedh08/AyuVahak/releases/tag/v0.1.0). Seven assets are attached: the 184,889,938-byte [local ZIP](https://github.com/Sumedh08/AyuVahak/releases/download/v0.1.0/AyuVahak-0.1.0-local.zip), [SHA256SUMS.txt](https://github.com/Sumedh08/AyuVahak/releases/download/v0.1.0/SHA256SUMS.txt), SPDX 3.0.1 SBOM, SPDX validation report, provenance, vulnerability inventory, and release evidence manifest. GitHub's reported SHA-256 digests match the local evidence pack; the ZIP digest is `ff4c383dbcdc0828d8de8fcf76e4d5d0a21413095c441dbc53b25076e5251df4`. The release notes state that this is a local reference package, not a production deployment; no DPG/clinical/legal certification or reuse license is claimed. The signed evidence bundle uses an ephemeral local acceptance identity and is not a public release trust signature.

The published ZIP was downloaded from GitHub into a clean temporary directory outside the repository and build tree. The checksum matched. In a fresh Empty installation, the app reached HTTP 200, all 93 migrations were present, and core organization/facility/Capacity/Referral/Transfer/Transport/Analytics/grant tables had zero rows. In a second clean Synthetic Demo extraction, the app reached HTTP 200, the explicit one-shot grant and versioned owner-API seed completed, and counts were 2 organizations, 2 facilities, 1 Capacity observation, 1 Referral revision, 1 Matching snapshot, 1 Transfer, 1 Transport request, and 1 Analytics snapshot. No federation peer or DHIS2 export operation was created. Both installations bound to loopback and were stopped afterward.

This acceptance-record update is documentation-only and will be integrated to the already-verified `main` and closeout branch with a normal fast-forward push. The release source tag and all freeze history remain unchanged.

The Phase 13 tag and all earlier freeze history must remain unchanged. Never force-push or move a published tag. If remote `main` appears or diverges before integration, reconcile by a normal merge/PR or stop without overwriting it.

## Final Volume V disposition

Local implementation, verification, acceptance, and independent review pass. The Volume V freeze commit follows this record, and the program-state governance follow-up records all five volumes complete and frozen. GitHub `main` integration and the `v0.1.0` release are complete; the published ZIP checksum and clean Demo/Empty package smoke passed. The subsequent change is acceptance-document reconciliation only.
