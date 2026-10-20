# Phase 13 standards and external-guidance evidence register

**Assessment date:** 2026-10-20. This register distinguishes a standard being referenced from
controls being implemented, tested, or externally assessed. A link, test, adapter, or local
acceptance run is not a certification or universal conformance claim.

| Standard or guidance | Version/basis used | Repository evidence | Evidence state | Limits / follow-up |
|---|---|---|---|---|
| HL7 FHIR | R4 as selected by the frozen interoperability profile | `docs/architecture/standards-and-interoperability.md`; Phase 8 acceptance; FHIR boundary tests | REFERENCED + LOCAL TEST EVIDENCE | No external FHIR endpoint was tested; conformance is profile- and partner-specific. |
| IHE mCSD / IPS | Frozen Volume III profile and referenced publication versions | Volume III architecture, Phase 8/9 acceptance and profile tests | REFERENCED + LOCAL TEST EVIDENCE | Does not imply IHE certification or partner conformance. |
| SMART App Launch | Version and boundary recorded in Volume III research/design | Volume III security/exchange records | REFERENCED | External identity-provider configuration and app registration remain deployment/governance work; none was created here. |
| DHIS2 Web API | Synthetic/local aggregate profile in Phase 12 | Phase 12 specification and acceptance; local mock only | REFERENCED + LOCAL TEST EVIDENCE | No public or shared DHIS2 was used. Acceptance does not prove a deployment's metadata mapping or public-health interpretation. |
| OWASP ASVS | 5.0.0 stable | [`phase-13-asvs-evidence-matrix.md`](phase-13-asvs-evidence-matrix.md); `ProductionProfileConfigurationTest`; security/API suites | REFERENCED + PARTIAL LOCAL EVIDENCE | No ASVS verification level or certification claim. Deployment-specific rows remain open. |
| OWASP API Security Top 10 | 2023 | Volume V research, threat models, rate-limit tests | REFERENCED + SELECTED LOCAL TEST EVIDENCE | Risk prompt only; not a complete security verification or measured AyuVahak prevalence model. |
| NIST SSDF | SP 800-218 v1.1 final | Volume V research; contributor/development policy; local release evidence helpers | REFERENCED + PARTIAL EVIDENCE | No organizational SSDF conformance assertion. SSDF v1.2 IPD remains draft basis, not final. |
| OpenSSF OSPS Baseline | `v2026.08.28` per research retrieval | Volume V research and DPG/readiness assessment | REFERENCED; GOVERNANCE PARTIAL | No badge, Scorecard result, OpenSSF certification, or externally hosted repository evidence is claimed. |
| SPDX | SPDX 3.0 design target; official schema/model/license assets pinned to SPDX 3.0.1 and SPDX License List 3.29.0 | `tools/release_evidence/` uses pinned local cdxgen 13.4.0, an offline normalizer/validator and official schema/SHACL/license assets; [Phase 13 acceptance](phase-13-acceptance.md) records generated package count, artifact/SBOM hashes and local validation evidence | LOCAL SPDX 3.0.1 GENERATION + VALIDATION TESTED | This does not provide a reviewed dependency vulnerability inventory or establish an eligible/public release. No SPDX 2.x substitute, production signer, publication, or conformance certification is claimed. |
| SLSA | v1.2 research basis | Local provenance helper binds source, builder, parameters and artifact digest | PARTIAL LOCAL PROVENANCE | No SLSA level is claimed; helper evidence is not hosted-builder identity or full SLSA conformance. |
| PostgreSQL | Version 18 documentation for recovery research; frozen runtime/test profile evidence is recorded per test | Migration tests; `BackupRestoreAcceptanceIT` with isolated PostgreSQL/PostGIS 18.6 logical dump/restore and Platform/Referral retention reapplication | REFERENCED + LOCAL LOGICAL-RESTORE TESTED | The latest acceptance record reports a previous synthetic run with a 0.195s dump-point-to-disaster RPO window and 8.121s restore-to-authorized-read RTO against local 15-minute targets. It does not prove PITR, WAL archive, encryption, off-host backup, scheduled backup, complete owner retention, or another deployment's objectives; the current canonical verification attempt could not start because `JAVA_HOME` was unavailable and Java 17 is below the project's Java 25 requirement. |
| Spring Boot | 4.1.1 implementation/research basis | Production configuration, health/management, shutdown tests and docs | REFERENCED + LOCAL TEST EVIDENCE | Actuator availability is not a monitoring service or production SLO. Ingress and host controls remain deployment-specific. |
| WCAG | 2.2 AA as a future human-interface target | Volume V DPG assessment | TARGET ONLY / CURRENT UI NOT ASSESSED | Current scope has no implemented AyuVahak browser UI. Assess any future UI and documentation surface. |

## Terms

- **Referenced:** the project names the standard and records an intended boundary.
- **Implemented:** repository code/configuration contains the relevant control.
- **Tested:** a local test provides evidence for the specified behavior.
- **Externally assessed/certified:** an authorized external assessment actually occurred. No such
  assessment is asserted here.

See [Volume V production/security/supply-chain research](../research/volume-5-production-security-supply-chain-research.md),
the [DPG research](../research/volume-5-dpg-standard-research.md), and the
[production-readiness matrix](production-readiness-gap-analysis.md) for basis, gaps, and caveats.
