# AyuVahak — Digital Public Goods Readiness Gap Analysis

**Assessment date:** 2026-10-20 (Asia/Calcutta)
**Status:** Internal evidence-based design assessment; not a DPGA application, legal opinion, registration, certification or recognition decision.
**Product baseline:** Frozen Volumes I–IV at the inspected local Volume IV baseline.
**Research basis:** [DPGA Standard research note](../research/volume-5-dpg-standard-research.md), which records the official current public page, the latest numbered release evidence and version caveat.

## Version and assessment boundaries

The official DPGA sources inspected are not synchronized: the DPG Standard page presents nine indicators; the DPGA-maintained repository/release list shows v1.1.6 (2024-09-04) while its current `standard.md` still labels v1.1.4; Secretariat materials describe 2025 AI-specific updates; and the October 2026 Practical Guide is Version 1.0 (guide version, not Standard version). This matrix therefore states its basis precisely and does not invent a newer numbered Standard version. Recheck the current official questionnaire and guidance before any future nomination.

The DPG Standard reviews the design of a core solution; it does not assess every local deployment. Repository evidence, deployment evidence and accountable project-owner/governance evidence are separated. “Partial” does not imply acceptance by DPGA.

## Criterion matrix

| Criterion | Status | Repository evidence at baseline | Gap / Phase 13 action | External or governance evidence needed |
|---|---|---|---|---|
| 1. SDG relevance | PARTIALLY SATISFIED | [Product vision](../product/vision.md) describes the coordination problem and intended public benefit without unsupported outcome guarantees. | Map the product purpose to specific SDG target(s), document theory of contribution and distinguish rationale from measured impact. | Owner/stakeholder decision on target and, if claimed, authorized deployment/impact evidence. |
| 2. Open licensing | NOT SATISFIED | No root `LICENSE`, `LICENSE.md` or `LICENSE.txt` was present in the inspected worktree; [vision](../product/vision.md) leaves licensing as a future decision. | Rights-holder selects and publishes an appropriate software license after rights/dependency review; do not infer or change it in Phase 13 design. | Rights holder and legal review; third-party rights and contributor provenance. |
| 3. Clear ownership | REQUIRES EXTERNAL/GOVERNANCE EVIDENCE | Architecture identifies technical ownership boundaries; no public project-owner/maintainer or asset-rights statement was found. | Create accountable project stewardship and rights inventory evidence; identify what belongs to the core project versus deployment. | Legal owner, jurisdiction, responsible steward and evidence of redistribution rights. |
| 4. Platform independence | PARTIALLY SATISFIED | Modular monolith, PostgreSQL/PostGIS, Java/Spring, OIDC, Compose and adapter boundaries are documented; [contributor guide](../development/contributor-guide.md) distinguishes local Compose from production. | Inventory mandatory dependencies, license/role, proprietary assumptions and replacement paths; document tested portable profiles. | Deployment verifies its selected DB, IdP, secret provider, runtime and platform. |
| 5. Documentation | PARTIALLY SATISFIED | Extensive README, contributor guide, architecture/specifications and Volume I–IV acceptance evidence exist. | Add adopter/operator documentation for deployment, configuration, security, upgrade, restore, troubleshooting and support. This design adds a Phase 13 spec but does not produce operational proof. | Named technical maintainer and support model; operational documentation validated by an adopter. |
| 6. Non-PII data extraction | NOT SATISFIED | APIs and aggregate DHIS2 output exist; no general documented non-PII portability/import-export facility was evidenced. | Inventory non-PII data/content and provide/document an open-format export/import path where safe; document exclusions. Preserve patient/clinical access controls and do not make PHI freely portable. | Data owner and deployment approval for export scope and format. |
| 7. Privacy and applicable laws | REQUIRES EXTERNAL/GOVERNANCE EVIDENCE | [Security and trust](../architecture/security-and-trust.md), Volume III/IV privacy/threat models and local synthetic-only acceptance provide product safeguards. | Publish clear privacy/data-use evidence and distinguish design controls from actual jurisdictional compliance. | Deployment controllers/operators, data purposes, lawful authority, consent where applicable, retention and jurisdiction-specific legal/privacy assessment. |
| 8. Standards and best practices | PARTIALLY SATISFIED | FHIR/IHE boundaries, API conventions, security architecture, local tests and phase acceptance are documented. | Maintain a criterion/version/scope/evidence/limitation/owner register and accessibility/localization assessment; map implementation evidence rather than listing standards only. | Independent/conformance or accessibility assessment where needed for the intended deployment/claim. |
| 9A. Data privacy and security | PARTIALLY SATISFIED | Explicit data minimization, access, audit, retention principles, threat/privacy matrices, Phase 9/11/12 boundaries and acceptance records exist. | Produce complete production security/retention/incident evidence and document residual deployment risks; test/operate actual controls. | Deployment configuration, operational evidence, responsible privacy/security owners, incident contacts and applicable legal review. |
| 9B. Inappropriate, misleading or illegal content | REQUIRES EXTERNAL/GOVERNANCE EVIDENCE | Product primarily exchanges structured coordination data; no general public UGC platform or moderation workflow is evidenced. | Document applicability for actual product content/communication surfaces. If any hosted/distributed content falls in scope, define reporting, review, removal and responsible owner. | Product-owner applicability determination and named content/safety response responsibility. |
| 9C. Protection from harassment | REQUIRES EXTERNAL/GOVERNANCE EVIDENCE | Internal authentication/grants exist; no public Code of Conduct, contributor complaint/report route, or underage-safety assessment was evidenced. | Document professional-user/community scope and necessary protection/report process; do not assume role grants are a CoC. | Maintainer/community governance, responsible contact, complaint handling and underage-safety applicability. |

## Status totals

| Status | Count (11 criterion rows) |
|---|---:|
| SATISFIED | 0 |
| PARTIALLY SATISFIED | 5 |
| NOT SATISFIED | 2 |
| REQUIRES EXTERNAL/GOVERNANCE EVIDENCE | 4 |
| NOT APPLICABLE | 0 |

These are internal baseline statuses, not DPGA judgments. The current design documents may clarify the required future work, but do not themselves close operational, legal or governance gaps.

## AI nomination boundary

Phase 14 keeps intelligence optional and disabled by default. The DPG AI-specific guidance applies if the nominated artifact is an AI system; it is not a reason to publish AyuVahak or deployment data. A future accountable owner must decide whether a nomination concerns the core software, an advisory component or an AI system and assess current AI-specific open licensing/data/code/model/documentation expectations. Phase 14 prohibits real health data and production-interaction training; privacy and safety rules remain controlling. No DPG submission or registration is authorized.

## Source notes

All official sources, version caveats, criteria interpretations and “does not imply” statements are captured in the [research note](../research/volume-5-dpg-standard-research.md), retrieved 2026-10-20. Key sources include the [DPG Standard](https://www.digitalpublicgoods.net/standard), the DPGA [October 2026 Practical Guide announcement](https://www.digitalpublicgoods.net/blog/a-practical-guide-to-the-dpg-standard), and the DPGA Secretariat's [AI systems guidance](https://www.digitalpublicgoods.net/blog/ai-systems-as-dpgs). Before an external assessment, use the then-current official questionnaire and evidence requirements.
