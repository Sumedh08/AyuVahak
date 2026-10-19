# Digital Public Goods Standard — Volume V Research Note

**Retrieved:** 2026-10-20 (Asia/Calcutta)
**Scope:** Official Digital Public Goods Alliance (DPGA) materials only; read-only documentation research. This is an implementation/design input, not a DPGA assessment or legal opinion.
**Repository basis inspected:** README.md, docs/product/vision.md, docs/product/scope-and-non-goals.md, docs/development/contributor-guide.md, docs/architecture/volume-4-privacy-matrix.md, docs/architecture/volume-4-threat-model.md, and the Volume IV acceptance record. Root AGENTS.md was read in full; no nested AGENTS.md exists in this worktree.

## 1. Current official basis and version caveat

The DPGA’s current [DPG Standard page](https://www.digitalpublicgoods.net/standard) describes the Standard as baseline criteria for recognizing nominated software, data, AI systems, and content collections. It lists nine indicators: SDG relevance; open licensing; clear ownership; platform independence; documentation; non-PII data extraction; privacy and applicable laws; standards and best practices; and Do No Harm, assessed through 9A data privacy/security, 9B inappropriate/illegal content, and 9C protection from harassment. The page explicitly says DPGA reviews the design of the core solution and does not assess local implementations in deciding whether a solution is a DPG.

The version evidence is not internally synchronized as of retrieval:

- The DPGA-maintained [DPGAlliance/dpg-standard README](https://github.com/DPGAlliance/dpg-standard) and its [release history](https://github.com/DPGAlliance/dpg-standard/releases) identify **v1.1.6**, published 2024-09-04, as the latest numbered release shown.
- The repository’s current [standard.md](https://github.com/DPGAlliance/dpg-standard/blob/main/standard.md) still labels itself **v1.1.4**, last updated 2021-01-04.
- DPGA’s May 2025 Secretariat article says the Standard Council introduced a set of updates enabling AI-system DPG nominations and describes AI-specific open data/code/model and documentation expectations: [AI systems as digital public goods](https://www.digitalpublicgoods.net/blog/ai-systems-as-dpgs). The [2025 ecosystem report](https://www.digitalpublicgoods.net/2025-DPG-Ecosystem-Report) and the Secretariat’s [June 2026 article](https://www.digitalpublicgoods.net/blog/navigating-alignment-in-ai-systems-as-dpgs) also describe the 2025 Standard update and its open-training-data threshold.
- The Secretariat’s [Practical Guide](https://content.digitalpublicgoods.net/A_Practical_Guide_to_the_DPG_Standard_0249fee444.pdf) is labeled **Version 1.0, October 2026**. This is the guide’s version, not an asserted Standard version. It explains all nine current indicator questions. DPGA announced it on [2026-10-15](https://www.digitalpublicgoods.net/blog/a-practical-guide-to-the-dpg-standard).

**Safe version statement for Volume V:** “DPG Standard current public basis reviewed 2026-10-20; DPGA repository’s latest numbered release shown is 1.1.6, while official Secretariat materials describe AI-specific Standard updates in 2025 and a Version 1.0 practical guide in October 2026. The official sources reviewed do not make the relationship between the numbered release and the later AI amendments unambiguous.” Do not relabel the Standard 1.1.7 or treat the stale standard.md as the sole current text. Recheck the official DPGA materials before any future external assessment or nomination.

## 2. Criterion-by-criterion interpretation for AyuVahak

Statuses below are **evidence observations for design planning only**, not DPGA findings. “Does not imply” records boundaries that prevent overclaiming. Repository evidence is distinguished from evidence requiring a named owner, deployment, legal/governance authority, or external review.

### 1. SDG relevance

- **Current requirement:** Demonstrate a credible link between the solution and at least one SDG, a real-world challenge, and evidence of relevance/impact. The 2026 guide says reviewers look for the solution description, selected SDGs, outcome/impact evidence, and implementation examples.
- **Does not imply:** A healthcare purpose, a mission statement, or an expected benefit proves measured impact or satisfies a DPGA reviewer.
- **Repository evidence:** docs/product/vision.md describes reducing coordination delay/uncertainty and explicitly avoids promising an outcome. It does not select SDG targets or present deployed impact evidence. The product vision calls DPG status an aspiration.
- **Implication:** Phase 13 should map the product’s intended public benefit to specific SDG targets without claiming causal outcomes; require deployment/impact evidence only when genuinely available and authorized. Keep a pre-deployment design rationale distinct from measured impact.

### 2. Open licensing

- **Current requirement:** Provide a public, approved license appropriate to the nominated category. For software, the official questionary points to the accepted-license list and requests public evidence of the project’s license.
- **Does not imply:** Publicly viewable source code, an open dependency, or a license on a third-party library licenses AyuVahak itself.
- **Repository evidence:** No root LICENSE, LICENSE.md, or LICENSE.txt was present in the inspected worktree. docs/product/vision.md says the project’s license remains a future decision.
- **Implication:** This is a material DPG-readiness gap and an owner/governance decision, not something Phase 13 should silently resolve. Record that the project is not currently evidenced as licensed for DPG recognition. A rights holder must choose and document the appropriate software license after reviewing ownership and third-party rights; do not change it as part of this research note.

### 3. Clear ownership

- **Current requirement:** Identify the accountable owner/steward and provide evidence of ownership or redistribution rights for the solution’s assets. The current DPGA questionary asks for owner, country/legal establishment, code/data/content ownership, and supporting public evidence.
- **Does not imply:** A Git author, maintainer, repository name, or successful build establishes the legal owner or rights to all contributions.
- **Repository evidence:** Architecture and product ownership boundaries are documented, but docs/product/vision.md identifies project ownership/community governance as future decisions. No owner/rights statement was found in the README or targeted governance files.
- **Implication:** Phase 13 should require a project-level ownership/maintainer statement and a rights inventory (including contributor and third-party component provenance) before claiming this criterion is evidenced. Accountable project-owner/legal evidence is external to code.

### 4. Platform independence

- **Current requirement:** Disclose mandatory technologies and dependencies; where a closed/proprietary dependency imposes restrictions, show independence from it or a functional open alternative that can replace it without major changes. DPGA’s guide asks for architecture, dependencies, proprietary components, alternatives, and deploy/operate documentation.
- **Does not imply:** A modular monolith, open standards, or cloud portability by intention proves every mandatory dependency is replaceable.
- **Repository evidence:** The architecture uses Java/Spring, PostgreSQL/PostGIS, OIDC, containers, and named interoperability standards. The contributor guide documents local Compose and describes adapter boundaries. No criterion-by-criterion proprietary-dependency and substitution analysis is present in the files inspected.
- **Implication:** Phase 13 should inventory core dependencies by role/license/mandatory status and document vendor-neutral interfaces and tested alternatives where any closed dependency is mandatory. Do not force Kubernetes or cloud products to satisfy this criterion.

### 5. Documentation

- **Current requirement:** A technical person unfamiliar with the project should be able to understand, use, deploy, modify, and maintain it. Evidence includes user/developer/technical documentation and usage instructions.
- **Does not imply:** A large specification set alone proves an adopter can operate or govern a production deployment.
- **Repository evidence:** README.md, the contributor guide, architecture indexes, phase specifications, and acceptance records provide extensive development and architecture navigation. The contributor guide is explicit that Compose is a local developer environment, not a production recipe. A user/operator handbook and production deployment/upgrade/recovery documentation are not evidenced by these files.
- **Implication:** Phase 13 should preserve the useful existing documentation and add the missing operator/adopter view: supported profiles, configuration, backup/restore, upgrade, security, troubleshooting, and governance. Document how a new technical adopter runs the local synthetic setup.

### 6. Non-PII data extraction

- **Current requirement:** Where the solution collects, uses, or generates non-personal data/content, provide a practical import/export mechanism in a non-proprietary format (for example CSV/JSON/XML or API) and document it.
- **Does not imply:** A DHIS2 outbound aggregate adapter or ordinary workflow API is automatically a portable bulk export/import facility for the deployment’s non-PII data.
- **Repository evidence:** The system exposes APIs and Phase 12 exports approved aggregates to DHIS2, but no general non-PII data portability/import-export guide was identified in the inspected documentation. The existing privacy boundary also forbids treating patient-level data as ordinary extractable content.
- **Implication:** Phase 13 should inventory non-PII data classes and document a safe, open-format portability path or explain why a class is not extractable. It must not use the DPG criterion to weaken patient-data, purpose, access, or disclosure controls.

### 7. Privacy and applicable laws

- **Current requirement:** Demonstrate that the solution is designed/managed to comply with relevant privacy and other applicable legal requirements, with evidence such as privacy policy, terms, collection/use practices, consent where relevant, and applicable-law analysis. DPGA’s current questionary requests the applicant’s list of relevant laws and supporting evidence.
- **Does not imply:** A generic security policy, privacy-by-design architecture, or citing a law proves legal compliance in every deployment or jurisdiction.
- **Repository evidence:** The repository contains explicit external-system/data safety policy, Volume III/IV threat/privacy architecture, minimum-necessary and ownership constraints, and local synthetic-only acceptance records. No public privacy notice, terms, or jurisdiction-specific legal applicability assessment was found in the inspected file set. AGENTS.md itself says this policy does not replace deployment privacy/legal governance.
- **Implication:** Phase 13 should define what evidence the product owner can state about the core solution and what each deployment must supply locally. Maintain jurisdictional configuration and legal review as governance evidence; do not claim universal compliance.

### 8. Standards and best practices

- **Current requirement:** Identify applicable open standards and relevant best practices/principles and demonstrate adherence. The October 2026 guide specifically names interoperability/API standards, accessibility practices, security best practices, and relevant assessments/certifications as examples of evidence.
- **Does not imply:** Listing a standard in a document proves implementation conformance; a test suite or certification is not mandatory for every standard unless the criterion/reviewer requires it.
- **Repository evidence:** The system has frozen FHIR R4/IHE interoperability specifications, architecture/security decisions, privacy/threat models, versioned context contracts, and acceptance evidence. The contributor guide documents architecture tests and local-only integration testing. A consolidated standards-to-implementation evidence register, accessibility assessment, and explicit best-practice conformance matrix were not found in the inspected files.
- **Implication:** Phase 13 should maintain a traceable standards/practices register: applicability, version, scope, evidence artifact, known limitation, and owner. Include accessibility and localization assessment appropriate to the API/documentation product, without inventing an unsupported UI requirement.

### 9A. Data privacy and security

- **Current requirement:** If implementers can collect/store/distribute PII, demonstrate how privacy, security, and integrity are ensured and how adverse impacts are mitigated. The official questionary also asks what categories are involved. DPGA’s 2025 AI guidance adds consideration of PII in AI training data if applying as an AI system.
- **Does not imply:** “No data in this local fixture,” an encryption statement, or passing tests alone proves production privacy/security across deployment configurations.
- **Repository evidence:** AyuVahak’s product and frozen architecture explicitly handle scoped patient references/context and contain security/trust, threat, privacy, audit, and external-exchange controls. Phase 8–12 acceptance records are repository evidence for the implemented core, not evidence for a specific production deployment’s lawful basis, configuration, operations, or user notices.
- **Implication:** Phase 13 should tie data classes to system controls, operational responsibilities, retention/access policy, incident response, and deployment evidence; describe the core design separately from deployer claims. Never present aggregate status as automatic anonymity.

### 9B. Inappropriate, misleading, or illegal content

- **Current requirement:** Where the solution collects, stores, or distributes content, provide suitable identification, moderation, reporting, and removal processes. The current questionary names inappropriate, misleading, and illegal content; the Standard landing page and some older standard text use the shorter “inappropriate & illegal” label, another reason to cite the current questionary/2026 guide rather than rely only on the stale Markdown.
- **Does not imply:** Every API exchange of structured operational data is a public content-hosting service, nor that the absence of a comments forum settles applicability.
- **Repository evidence:** AyuVahak exchanges structured healthcare interoperability data and limited transient clinical context; the inspected docs do not establish an open user-generated-content platform or a content moderation/report/removal process.
- **Implication:** Phase 13 must document a product-owner applicability decision based on actual data/content flows. If any content falls in scope, specify the bounded detection/report/review/removal responsibilities and response path. Do not add broad moderation machinery if the product does not host such content.

### 9C. Protection from harassment

- **Current requirement:** If the solution facilitates interactions with or between users/contributors, provide protection/reporting processes and address safety/security of underage users. The current questionary requests policy/process and underage-safety evidence.
- **Does not imply:** An internal role/grant model is automatically a community Code of Conduct or an underage safeguarding process; conversely, a professional-only service does not automatically make applicability obvious to reviewers.
- **Repository evidence:** AyuVahak has authenticated operator and contributor/developer contexts, but no root CODE_OF_CONDUCT.md, public contributor-safety policy, reporting channel, or underage-safety determination was present in the inspected file set. The primary operational user model is professional/coordinator, not a consumer social network.
- **Implication:** Phase 13 should record a grounded applicability decision and, where applicable, ensure contributor and operator protection/complaint paths are explicit. Community Code of Conduct and designated responsible contact require project governance, not just code.

## 3. AI-specific DPG consequence for Phase 14

The DPGA Secretariat’s [May 2025 AI-specific explanation](https://www.digitalpublicgoods.net/blog/ai-systems-as-dpgs) says an AI-system DPG must make its training/validation/test datasets and relevant preprocessing/training/validation/testing code available under the specified open-license categories, as well as model architecture and parameters; its documentation includes intended use, metrics, limitations/bias, dataset provenance/composition/preprocessing, and responsible-AI risk information. The Secretariat’s [June 2026 clarification](https://www.digitalpublicgoods.net/blog/navigating-alignment-in-ai-systems-as-dpgs) describes open training data as a threshold rather than a gradient and says the Standard does not itself mandate localization.

This is a criterion for DPG recognition of an **AI system**, not a reason to publish sensitive AyuVahak data or to make AI mandatory. AyuVahak Phase 14 should keep intelligence disabled by default and non-authoritative, exclude patient/clinical data, avoid automatic production-data training, and keep the core software useful without models. A future DPG assessment must explicitly decide whether the nominated artifact is the core open-software platform, a separately packaged advisory component, or an AI system; do not describe an optional proprietary/closed model feature as an AI DPG without meeting applicable AI-component openness requirements. No open-data requirement overrides AyuVahak’s privacy or safety constraints.

The DPGA article also says comprehensive AI risk/responsibility frameworks are encouraged but not required for DPG recognition. That does not remove the independent Phase 14 safety design requirements; nor does it make an AI feature DPG-eligible by itself.

## 4. Assessment/process boundaries

- DPG alignment, an internal gap matrix, or positive architecture review is not registration, certification, or DPGA recognition. DPGA describes an application/review process and registry listing for successfully reviewed solutions; its FAQ says the Standard can evolve and applications are reviewed against the then-current version.
- The Standard targets the design of the core solution, not each local implementation. Phase 13 must therefore distinguish product-level evidence from deployment-specific evidence such as real operating history, local legal review, support arrangements, country impact, or selected deployment controls.
- Human-authorized ownership, jurisdictional legal applicability, SDG impact, stakeholder/governance practices, public project/licensing links, and responsible contact details cannot be fabricated by code or inferred from a repository.
- No nomination, submission, contact, account creation, or registry action was performed or authorized in this research task.

## 5. Recommended Phase 13 DPG work products

1. A dated criterion-by-criterion matrix using the current public DPGA page, the October 2026 guide, the current questionary, and the 2025 AI amendment as basis; retain source/version caveats above.
2. Separate columns for core-solution evidence, deployment-specific evidence, and accountable-owner/governance evidence; statuses must not be inferred from intentions.
3. A project rights/ownership decision record before any license or public DPG claim.
4. A dependency/platform-independence inventory and portable non-PII import/export documentation that preserves the existing PHI boundary.
5. A documented privacy/legal, content-applicability, user-safety, accessibility, and standards-evidence assessment, with deployment-level responsibilities separated.
6. A hard statement that DPG registration/submission requires a separate human authorization and that readiness work makes no certification claim.

## 6. Official sources reviewed (read-only; retrieved 2026-10-20)

1. DPGA, [Digital Public Goods Standard](https://www.digitalpublicgoods.net/standard) — current indicators and core-solution/local-implementation disclaimer.
2. DPGA, [DPGAlliance/dpg-standard repository](https://github.com/DPGAlliance/dpg-standard) and [releases](https://github.com/DPGAlliance/dpg-standard/releases) — version/release basis.
3. DPGA, [current repository standard.md](https://github.com/DPGAlliance/dpg-standard/blob/main/standard.md) and [current standard-questions.md](https://github.com/DPGAlliance/dpg-standard/blob/main/standard-questions.md) — text/version mismatch and application questions.
4. DPGA Secretariat, [A Practical Guide to the DPG Standard, v1.0, October 2026](https://content.digitalpublicgoods.net/A_Practical_Guide_to_the_DPG_Standard_0249fee444.pdf), announced [2026-10-15](https://www.digitalpublicgoods.net/blog/a-practical-guide-to-the-dpg-standard) — current explanatory guide for the nine indicators.
5. DPGA Secretariat, [AI systems as digital public goods](https://www.digitalpublicgoods.net/blog/ai-systems-as-dpgs), 2025-05-21 — AI-system component, openness, documentation, and responsible-AI requirements.
6. DPGA Secretariat, [2025 State of the DPG Ecosystem / AI milestones](https://www.digitalpublicgoods.net/2025-DPG-Ecosystem-Report) — 2025 update context.
7. DPGA Secretariat, [Navigating Alignment in AI Systems as DPGs](https://www.digitalpublicgoods.net/blog/navigating-alignment-in-ai-systems-as-dpgs), 2026-06-23 — current AI openness position and limits.
8. DPGA, [Application FAQs](https://www.digitalpublicgoods.net/digital-public-goods/faqs) — nomination/review process and evolving standard context.

No public or live healthcare system was contacted. No DPG nomination or external mutation was attempted in this research task.
