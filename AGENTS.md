# AyuVahak Agent Safety and Working Policy

## External Systems and Agent Safety

This policy applies to every AyuVahak phase and must be followed by all agents working in this repository.

### External mutation is forbidden by default

Do not perform state-changing operations against any external service unless the human user explicitly authorizes that exact class of operation. This includes public/shared FHIR servers; HAPI FHIR; DHIS2 demo, play, development, staging, or public servers; OpenMRS; OpenHIE/OpenHIM; OpenLMIS; WHO and IHE services; Hugging Face; GitHub and GitLab; package and container registries; cloud accounts; SaaS services; government systems; public APIs; third-party databases; external issue trackers; and public test systems.

Do not POST, PUT, PATCH, DELETE, run mutation GraphQL, upload, publish, submit, deploy, or perform an equivalent remote state change without that explicit authorization. This applies after Phase 0 too, including public/open-source upstream repositories and registries. If it is unclear whether an operation mutates remote state, treat it as forbidden.

### Git remote writes are forbidden in Phase 0

Local Git operations are allowed, including inspecting status/history, creating local branches, and making local commits if useful. During Phase 0, do not push (including force-push), create remote branches, create GitHub issues or pull requests, create releases, modify repository settings, deliberately trigger remote workflows, or modify third-party repositories. Do not push to the AyuVahak remote during Phase 0.

### Public healthcare systems are never test targets

Never test AyuVahak by writing data into public/shared healthcare systems, even when described as demo, sandbox, test, development, public, or playground. Future integration tests must use local containers, local mock servers, deterministic fixtures, recorded/static standards examples, or disposable infrastructure controlled by AyuVahak developers. Public FHIR/DHIS2 systems may be researched through documentation and, only where necessary, read-only retrieval.

### Research network access

Internet research is permitted in Phase 0; prefer official and primary sources. Read-only HTTP retrieval of documentation is acceptable. Never bypass authentication, authorization, robots restrictions, rate limits, access controls, or technical restrictions. If access is denied, stop attempting to access that resource. Do not probe for vulnerabilities, enumerate hidden resources, attempt alternate paths to circumvent restrictions, submit forms, create accounts, or authenticate into external healthcare infrastructure. If an architectural research question cannot be answered safely, record the uncertainty and continue with the safest reasonable assumption.

### Secrets and health information

Do not request, generate, store, expose, commit, or transmit real production credentials. No real API keys, OAuth credentials, private keys, passwords, healthcare-system credentials, or cloud credentials are needed for Phase 0. Do not obtain or store real patient data or PHI/PII from healthcare systems. Use fictional identifiers and synthetic information in all examples and diagrams.

### Phase boundary and local planning

Phase 0 is product definition, domain language, system boundaries, standards strategy, security/trust assumptions, architecture decisions, and documentation only. Do not implement the application, scaffold services or applications, create database schemas, implement APIs/connectors, or build production functionality. Do not proceed to `/to-spec`, `/to-tickets`, `/implement`, or `/implement-spec` during Phase 0. Stop when the Phase 0 architecture documentation is complete. If a planning skill requires an issue tracker, use a local Markdown tracker or another local-only mechanism; never create external issues for Phase 0.

## Documentation discipline

Treat project-owner working decisions as constraints to document and stress-test, not silently replace. Record material counterarguments, unresolved questions, and safe assumptions in the architecture documentation and ADRs. Keep domain terminology consistent across product, workflow, data-ownership, and bounded-context documents. Future agents must read and follow this policy before acting.

## Agent skills

### Issue tracker

Planning and decision records use local Markdown under `.scratch/`; no external tracker writes are allowed. See `docs/agents/issue-tracker.md`.

### Triage labels

Use the five default local triage roles when a future planning workflow needs them. See `docs/agents/triage-labels.md`.

### Domain docs

Single product domain: `GLOSSARY.md` for terms, `CONTEXT.md` for higher-level domain context, and `docs/adr/` for durable architecture decisions. Read `docs/architecture/constitution.md` and the relevant architecture documents before later specifications or implementation. See `docs/agents/domain.md`.

