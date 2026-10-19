# Volume V Production Security and Supply-Chain Research

**Retrieved:** 2026-10-20 (Asia/Calcutta)
**Scope:** Phase 13 production hardening, application security, source/build/release security, backup and restore, Java/Spring operations, and container artifact identity. Official primary sources only. No external service was used as a test target; no account, artifact, or remote state was created or changed.

This note records versioned facts for architecture and specification decisions. It is not a security certification, compliance attestation, DPG registration, deployment profile approval, or claim that AyuVahak currently satisfies any cited control.

## NIST Secure Software Development Framework

### NIST SP 800-218 v1.1 final

- **Retrieved:** 2026-10-20.
- **Version/basis:** NIST SP 800-218, *Secure Software Development Framework (SSDF) Version 1.1*, final, published 2022-02-03.
- **Fact:** NIST describes SSDF as high-level practices intended to be integrated into an organization’s existing SDLC. It provides shared vocabulary and practices intended to reduce vulnerabilities, mitigate exploitation impact, and address root causes.
- **What this does not imply:** SSDF is not a particular CI vendor, scanner, lifecycle methodology, or a guarantee that software is secure. A mapping to SSDF does not itself establish implementation evidence or conformance.
- **AyuVahak consequence:** Use SSDF as the stable outcome-oriented backbone for Phase 13’s secure development, protected build/release, vulnerability response, and evidence matrix. Map controls to repository and operating evidence rather than selecting tools by framework name.
- **Primary source:** [NIST SP 800-218 final](https://csrc.nist.gov/pubs/sp/800/218/final).

### NIST SP 800-218 Rev. 1 v1.2 initial public draft

- **Retrieved:** 2026-10-20.
- **Version/basis:** NIST SP 800-218 Rev. 1, SSDF v1.2, Initial Public Draft (IPD), published 2025-12-17; comment period closed 2026-01-30. NIST’s publication page still labels it a draft at retrieval.
- **Fact:** The IPD describes proposed new/improved practices for secure and reliable software development, delivery, and improvement.
- **What this does not imply:** A closed comment period does not make an IPD final or supersede v1.1. Draft requirements must not be represented as final normative requirements.
- **AyuVahak consequence:** Pin Phase 13’s required baseline to final v1.1 and keep v1.2 IPD as a review watch item. Reassess when NIST publishes a final revision; do not defer implementation-ready design awaiting that future status change.
- **Primary source:** [NIST SP 800-218 Rev. 1 IPD](https://csrc.nist.gov/pubs/sp/800/218/r1/ipd).

## OWASP application and API security

### OWASP Application Security Verification Standard

- **Retrieved:** 2026-10-20.
- **Version/basis:** OWASP ASVS 5.0.0, stable release dated May 2025. The project marks the live `master` branch as bleeding edge and directs production users to stable versions.
- **Fact:** ASVS is an open, comprehensive set of security requirements for designing, developing, and testing web applications and services. Requirement identifiers can change between versions; OWASP recommends version-qualified identifiers for references.
- **What this does not imply:** ASVS does not certify AyuVahak, select a universal verification level, or prove controls merely because requirements are cited. A changing development branch is not a stable baseline.
- **AyuVahak consequence:** Use version-qualified ASVS 5.0.0 requirements to create a risk-scoped application/API verification matrix in Phase 13; select the applicable requirements and evidence for the API-only product surface. Record exclusions with rationale and review the stable version before a future release.
- **Primary source:** [OWASP ASVS project and stable version](https://github.com/OWASP/ASVS).

### OWASP API Security Top 10

- **Retrieved:** 2026-10-20.
- **Version/basis:** OWASP API Security Top 10, 2023 edition.
- **Fact:** OWASP characterizes it as a forward-looking awareness document. The edition highlights object/property/function authorization, resource consumption, sensitive business-flow abuse, and unsafe consumption of integrated APIs. Its risk ratings are consensus-based rather than derived from submitted prevalence data, and the document says it does not replace other Top 10s or perform an application-specific risk analysis.
- **What this does not imply:** It is not a complete verification standard, a quantitative AyuVahak risk model, or an instruction to add a vendor product. The list alone cannot establish endpoint authorization or acceptable rate limits.
- **AyuVahak consequence:** Use it as a threat-review checklist for API abuse and resource exhaustion, while testable control requirements come from scoped ASVS and AyuVahak’s threat model. Define per-class limits from observed resource budgets and authorized identity/scope, not IP address alone.
- **Primary source:** [OWASP API Security Top 10 2023 release notes](https://api-security.owasp.org/editions/2023/en/0x04-release-notes/).

## OpenSSF project and dependency security

### Open Source Project Security Baseline

- **Retrieved:** 2026-10-20.
- **Version/basis:** OpenSSF OSPS Baseline `v2026.08.28`, marked current on the official baseline site at retrieval. Its levels describe maturity contexts; the site states downstream users should identify the specific version used. It also warns that mappings to external frameworks are not guaranteed one-to-one equivalences.
- **Fact:** The baseline has concrete project controls covering repository access, least privilege for pipelines, untrusted workflow input, dependency inventories, release/version identity, signed or hash-covered assets, build/test documentation, security contacts, coordinated disclosure, threat modeling, SBOM availability, and vulnerability/license remediation policy. Levels distinguish any project (Level 1), smaller multi-maintainer projects (Level 2), and projects with larger consistent user communities (Level 3).
- **What this does not imply:** Assessing a level is not a security certification or proof that a deployed installation is secure. A crosswalk to SSDF/other frameworks is informative, not automatic compliance. Project-level criteria also depend on governance systems outside this repository.
- **AyuVahak consequence:** Pin the baseline release in the Phase 13 DPG/production gap analysis and assess each applicable item with repository evidence or a clearly marked external/governance dependency. Do not silently label absent hosting controls as satisfied. Revalidate the official “current” version for future assessment updates.
- **Primary source:** [OpenSSF OSPS Baseline v2026.08.28](https://baseline.openssf.org/versions/2026-08-28).

### OpenSSF Scorecard and Best Practices Badge

- **Retrieved:** 2026-10-20.
- **Version/basis:** Current OpenSSF Scorecard project guidance and Best Practices Badge description, retrieved 2026-10-20.
- **Fact:** Scorecard runs automated checks and returns individual scores/risk levels (the project page describes a score out of 10); OpenSSF presents it as an automated risk-assessment aid for open-source projects. The Best Practices Badge is a voluntary self-certification questionnaire; it supports a MUST-focused OSPS Baseline series as well as “metal” maturity series.
- **What this does not imply:** A high score is not a security verdict; unmeasured governance and deployment risks remain. A voluntary badge/self-assessment is not independent certification. Running a scanner does not replace a manual threat assessment or vulnerability response process.
- **AyuVahak consequence:** Phase 13 may use Scorecard checks as signals and OSPS as an evidence checklist, but must not gate the product solely on one aggregate score or claim “secure” based on a badge. Any project scan must be locally authorized and must not probe external targets; this research did not run Scorecard on the repository.
- **Primary sources:** [OpenSSF Scorecard](https://openssf.org/projects/scorecard/); [OpenSSF Best Practices Badge](https://openssf.org/projects/best-practices-badge/).

### Vulnerability prioritization signal

- **Retrieved:** 2026-10-20.
- **Version/basis:** CISA Known Exploited Vulnerabilities (KEV) Catalog guidance, current catalog page retrieved 2026-10-20.
- **Fact:** CISA calls the KEV Catalog an authoritative source of vulnerabilities known to have been exploited in the wild and says organizations should use it as an input to vulnerability-prioritization frameworks. CISA distinguishes its binding federal directive from its broader recommendation to other organizations.
- **What this does not imply:** KEV is not a complete vulnerability inventory, universal law, severity score, or sole prioritization algorithm. Absence from KEV does not mean safe; its federal remediation deadlines do not automatically apply to AyuVahak deployments.
- **AyuVahak consequence:** Vulnerability policy should combine severity, exploit evidence/KEV status, exposure, reachable affected code, fix availability, and deployment context. Require a documented owner, rationale, and expiry/review date for exceptions; define response-time classes as deployment/project policy rather than copying federal deadlines.
- **Primary source:** [CISA Known Exploited Vulnerabilities Catalog](https://www.cisa.gov/known-exploited-vulnerabilities-catalog).

## Build provenance, SBOM, and artifact identity

### SLSA provenance

- **Retrieved:** 2026-10-20.
- **Version/basis:** SLSA specification v1.2, status Approved on the official site; Build Track and verification guidance retrieved 2026-10-20.
- **Fact:** SLSA v1.2 defines levels/tracks describing increasing provenance trust and completeness. Build L1 requires provenance to exist; Build L2 adds a hosted build platform and builder-generated signed provenance; Build L3 adds hardened build isolation. Provenance should identify the output by cryptographic digest. Verification guidance requires checking trusted builder identity, signature, build type, and expected parameters; the spec cautions that provenance has value only when consumers inspect it.
- **What this does not imply:** CI presence is not a SLSA level. A signed statement alone does not prove the builder is trustworthy or that inputs/configuration are expected. A project must not claim a level without evidence for all level requirements and an identified trusted build platform.
- **AyuVahak consequence:** Phase 13 should require per-release artifact digests and machine-verifiable build provenance, then document verifier instructions and expected builder/source identities. Treat SLSA Build L1 as the minimum provenance vocabulary; target signed, builder-generated provenance at a level only after the selected platform demonstrably satisfies it. Avoid a cloud-provider requirement and avoid claiming a SLSA level from design intent.
- **Primary sources:** [SLSA v1.2 specification](https://slsa.dev/spec/v1.2/); [SLSA Build requirements](https://slsa.dev/spec/v1.2/build-requirements); [SLSA artifact verification](https://slsa.dev/spec/v1.2/verifying-artifacts).

### SBOM format choice: SPDX 3.0

- **Retrieved:** 2026-10-20.
- **Version/basis:** Official SPDX specifications page lists SPDX 3.0 as current and identifies SPDX as ISO/IEC 5962:2021; SPDX 2.3 is listed as a previous version.
- **Fact:** SPDX is an international open standard for software package information, with versioned machine-readable formats; the current official specification page lists v3.0 and HTML/SHACL formats.
- **What this does not imply:** ISO standard status does not guarantee a particular scanner or downstream buyer supports every SPDX version/profile. An SBOM is not a vulnerability assessment, complete proof of every transitive runtime component, license opinion, or security guarantee.
- **AyuVahak consequence:** Prefer one SPDX 3.0 SBOM per distributable release artifact, including stable component identities, versions, relationships, license expressions when known, and cryptographic hashes where available. Require format/schema validation and document generation coverage/limitations. The Phase 13 implementation can select a generator only after verifying that it emits the selected profile; do not generate duplicate formats without a consumer requirement.
- **Primary source:** [SPDX specifications](https://spdx.dev/use/specifications/).

### CycloneDX as an alternative, not a second required SBOM

- **Retrieved:** 2026-10-20.
- **Version/basis:** Official CycloneDX specification overview lists v1.7 as current, released 2025-10-21 and published as ECMA-424 on 2025-12-10.
- **Fact:** CycloneDX describes a modular object model for components, services, relationships, lifecycle and additional supply-chain inventories, with JSON/XML/Protobuf documentation. The project recognizes a CycloneDX predicate type for attestations.
- **What this does not imply:** Broader modeling capability does not prove better tool compatibility or security. Supporting two SBOM formats adds synchronization and validation work; there is no requirement to do so absent a consumer need.
- **AyuVahak consequence:** Record CycloneDX 1.7 as a credible alternative if later consumer/tool compatibility materially favors it, but keep a single canonical SPDX profile in the initial Phase 13 design. Revisit only with concrete evidence, not because a newer version exists.
- **Primary source:** [CycloneDX specification overview](https://cyclonedx.org/specification/overview/).

### Signatures, signer identity, and OCI digests

- **Retrieved:** 2026-10-20.
- **Version/basis:** Current official Sigstore Cosign signing/verification documentation and Docker documentation on image digests, retrieved 2026-10-20.
- **Fact:** Sigstore Cosign can sign image digests and attestations and supports identity-based (OIDC/keyless) signing as well as managed-key modes. Its documentation says keyless signing associates an ephemeral key with an OIDC identity; verification can check the identity/issuer, claims, and transparency evidence. Docker documents that a digest is an immutable content identifier while tags are mutable.
- **What this does not imply:** A valid signature does not mean the artifact is secure; the verifier must check the expected signer identity, issuer, digest, and provenance claims. Keyless signing is not independent of trust infrastructure: its OIDC identity and transparency evidence need policy and availability treatment. A pinned digest will not automatically pick up a security fix.
- **AyuVahak consequence:** Require releases to provide authenticated signatures (or a signed manifest covering every release asset and digest), verification instructions, and explicit expected signer identity. Prefer an interoperable signature/attestation profile; Sigstore is a candidate, not a mandatory hosted provider. For container-based profiles, deploy by reviewed digest and pair pinning with a scheduled base-image/runtime update process so immutability does not freeze vulnerable layers. Do not select a registry, cloud KMS, or signing identity in architecture.
- **Primary sources:** [Sigstore Cosign container signing](https://docs.sigstore.dev/cosign/signing/signing_with_containers/); [Sigstore verification](https://docs.sigstore.dev/cosign/verifying/verify/); [Docker image digests](https://docs.docker.com/dhi/explore/security-concepts/digests/); [Docker base-image pinning guidance](https://docs.docker.com/build/building/best-practices/).

## PostgreSQL backup and recovery

### PostgreSQL 18 backup methods and PITR

- **Retrieved:** 2026-10-20.
- **Version/basis:** PostgreSQL 18 official documentation, Chapter 25, Backup and Restore, and Section 25.3 Continuous Archiving and Point-in-Time Recovery.
- **Fact:** PostgreSQL describes logical SQL dumps, filesystem-level backups, and continuous archiving as distinct approaches. Continuous archiving combines a base backup and WAL archive to support recovery to a selected point in time; PostgreSQL notes it is more complex to administer but often preferred for higher reliability. `pg_dump`/`pg_dumpall` are logical and cannot serve as the filesystem base for WAL replay. WAL archive failures are retried, and lagging archives can accumulate WAL and consume disk. WAL replay does not restore manually edited configuration files; those need separate backup. Restore targets and retention must preserve complete base/WAL sets.
- **What this does not imply:** Enabling WAL archiving alone proves neither backup completeness nor successful recovery. PITR does not define suitable RPO/RTO, protect external secret/configuration systems, or validate application-level invariants. A standby/replica is not a substitute for independent backup and restore proof.
- **AyuVahak consequence:** Let each deployment choose a recovery design from an explicit RPO/RTO class. For classes requiring PITR, specify monitored base-backup/WAL archival, protected off-host copies and configuration/secret restoration procedures. Acceptance must restore a synthetic backup to an isolated disposable PostgreSQL instance, boot the app at the intended supported migration version, and verify critical read/workflow invariants. Record measured restore time and data-loss boundary rather than inferring them from backup configuration.
- **Primary sources:** [PostgreSQL 18 Backup and Restore](https://www.postgresql.org/docs/18/backup.html); [PostgreSQL 18 Continuous Archiving and PITR](https://www.postgresql.org/docs/18/continuous-archiving.html).

## Spring Boot and Java runtime operations

### Spring Boot Actuator

- **Retrieved:** 2026-10-20.
- **Version/basis:** Spring Boot 4.1.1 reference documentation for production-ready features, endpoints, metrics, and graceful shutdown.
- **Fact:** Spring Boot Actuator supplies health/audit/metrics capabilities and Micrometer integration, with configurable endpoint exposure. The endpoints reference says only `/health` is exposed over HTTP by default and warns that exposed actuators must not contain unprotected sensitive details. It distinguishes liveness and readiness and advises that liveness should not depend on external systems to avoid cascading restarts. Graceful shutdown is enabled by default for supported embedded servlet/reactive web servers and bounds request drain with a configurable shutdown-phase timeout. The `metrics` endpoint is diagnostic; production monitoring should export to an external metrics backend rather than scrape that endpoint as the backend.
- **What this does not imply:** An enabled health endpoint is not complete observability, a secured management plane, or an SLO. Actuator does not choose a monitoring provider or ensure labels avoid PHI/high cardinality. Graceful shutdown cannot finish unbounded work or make in-memory work durable.
- **AyuVahak consequence:** Specify separate liveness/readiness semantics, minimal public health details, protected/isolated management surfaces, bounded shutdown/drain, and actual exported service SLIs. Keep durable work in the existing database/outbox, not an in-memory shutdown queue. Add tests that management endpoints are unavailable except by explicit protected configuration.
- **Primary sources:** [Spring Boot 4.1.1 production-ready features](https://docs.spring.io/spring-boot/reference/actuator/); [Actuator endpoint exposure and security](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html); [Actuator metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html); [graceful shutdown](https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html).

## Release identity and HTTP response hardening

### Semantic Versioning 2.0.0

- **Retrieved:** 2026-10-20.
- **Version/basis:** Semantic Versioning Specification 2.0.0, current official specification page.
- **Fact:** SemVer defines MAJOR.MINOR.PATCH compatibility claims for a declared public API and prerelease/build metadata. Its compatibility promise depends on the project actually defining what its public API is.
- **What this does not imply:** A SemVer string is not evidence that compatibility was tested, that database migrations are reversible, or that an API URI version must equal the application release version.
- **AyuVahak consequence:** Use SemVer for stable application releases after the project declares the supported public product/API surface. Keep API URI major version governed separately by the existing API version decision. Pre-release and `SNAPSHOT` builds are not production release identifiers. Each immutable release also binds source revision, migration compatibility, artifact digest, SBOM, provenance and signature.
- **Primary source:** [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html).

### OWASP Secure Headers Project

- **Retrieved:** 2026-10-20.
- **Version/basis:** OWASP Secure Headers Project current response-header and best-practice pages. The project identifies its broad recommendations as a working draft and marks individual headers active/deprecated.
- **Fact:** The project distinguishes browser-enforced headers from general response behavior. It describes HSTS as a browser policy delivered over HTTPS and cautions against including the preload directive by default. It notes CSP applicability depends on the document execution context; in an API-only product, CSP is generally authored by the browser-consuming document rather than applied as if JSON were HTML. It discusses `nosniff`, cache directives, frame controls, CORS and removal of technology-disclosing headers.
- **What this does not imply:** A bulk recommended-header list is not a drop-in policy for every JSON endpoint, proxy or browser. Adding irrelevant or misconfigured headers may have no value or may break clients. A working draft is not a normative application-specific requirement.
- **AyuVahak consequence:** Specify a response-header policy by surface: HTTPS ingress may set HSTS after the operator confirms the complete hostname/subdomain HTTPS scope (no default preload); API responses use appropriate content type, `X-Content-Type-Options: nosniff`, no-store for sensitive responses, and avoid software/version disclosure; CORS is explicit and credential-aware. CSP/frame protections apply to any actual HTML/browser-delivered interface/docs surface with a tested policy, not mechanically to JSON. Validate the final deployment ingress/application combination.
- **Primary sources:** [OWASP Secure Headers — response headers](https://secure-headers-project.owasp.org/response-headers/); [OWASP Secure Headers — best practices](https://secure-headers-project.owasp.org/best-practices/).

### Java SE 25 diagnostics

- **Retrieved:** 2026-10-20.
- **Version/basis:** Oracle Java SE 25 Troubleshooting and Monitoring and Management guides.
- **Fact:** The Java SE 25 guide describes runtime diagnostics including JVM metrics, JMX, `jcmd`, and Java Flight Recorder (JFR). Oracle describes JFR as low-overhead and designed for production use; its troubleshooting guidance gives it as a tool for locating I/O, CPU, synchronization, GC, and memory issues. Java documentation advises using a current update release because runtime bugs are fixed in update releases.
- **What this does not imply:** “Low overhead” does not mean zero overhead or unlimited recording retention. JFR/core/heap dumps may contain sensitive runtime state; exposing remote JMX is a separately secured control. A specific Java vendor/distribution or exact production flags are not selected by this research.
- **AyuVahak consequence:** Preserve a supported Java LTS/runtime update policy and include diagnostic capture as an operator capability with bounded retention, access control, and incident handling. Enable only measured/needed telemetry; do not dump heap or expose management interfaces by default.
- **Primary sources:** [Java SE 25 Troubleshooting Guide](https://docs.oracle.com/en/java/javase/25/troubleshoot/index.html); [Prepare Java for Troubleshooting](https://docs.oracle.com/en/java/javase/25/troubleshoot/prepare-java-troubleshooting.html); [Java Flight Recorder performance troubleshooting](https://docs.oracle.com/en/java/javase/25/troubleshoot/troubleshoot-performance-issues-using-jfr.html).

## Research boundaries and design summary

- No external vulnerability target, healthcare system, registry, release endpoint, or cloud account was contacted. No security tool was run against AyuVahak or a third party.
- This report does not assess DPG Standard criteria, current licensing of the repository, jurisdiction-specific retention/legal requirements, or accessibility applicability. Those require the dedicated DPG/gap-analysis workstream and repository evidence. Do not infer that accessibility is inapplicable merely because the current surface is API-oriented.
- Use versioned standards as review baselines, not certification claims.
- Keep the production architecture portable: PostgreSQL remains the system of record; security controls are requirements and evidence, not mandates for Kubernetes, one cloud, one scanner, or a particular hosted release provider.
- Require backup restore proof, not just configured jobs; signatures plus provenance verification, not a bare image digest; and vulnerability triage with exceptions, not “zero known CVEs” as a security promise.
- Set service-level targets and retention through deployment/governance configuration after workloads and recovery capability are measured; this research does not invent one universal healthcare SLA, RPO/RTO, or retention duration.
