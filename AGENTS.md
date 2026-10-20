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

#### Current program state

- Phase 0 — Product & Architecture Constitution: FROZEN
- Phase 1 — Engineering Platform: FROZEN
- Phase 2 — Platform Kernel, Identity & Trust: FROZEN
- Phase 3 — Care Service Directory: FROZEN
- Phase 4 — Capacity & Operational Readiness: FROZEN
- Phase 5 — Referral: FROZEN
- Phase 6 — Destination Matching & Recommendation: FROZEN
- Phase 7 — Transfer Coordination: FROZEN
- Volume I — Engineering Platform: COMPLETE + FROZEN
- Volume II — Core Care Coordination: COMPLETE + FROZEN
- Volume III — COMPLETE + FROZEN
- Phase 8 — Healthcare Standards & Interoperability: IMPLEMENTATION COMPLETE + FROZEN
- Phase 9 — Secure External Health-System Exchange: IMPLEMENTATION COMPLETE + FROZEN
- Phase 10 — Emergency Transport / EMS Coordination: IMPLEMENTATION COMPLETE + FROZEN
- Volume IV — DESIGN FROZEN
- Phase 11 — IMPLEMENTATION COMPLETE + FROZEN
- Phase 12 — IMPLEMENTATION COMPLETE + FROZEN
- Volume IV — COMPLETE + FROZEN
- Volume V — DESIGN FROZEN
- Phase 13 — IMPLEMENTATION AUTHORIZED BUT INCOMPLETE
- Phase 14 — SPECIFICATION FROZEN / IMPLEMENTATION NOT AUTHORIZED
- Volume V — NOT YET COMPLETE

#### Phase-aware execution rules

- Treat frozen phases as stable baselines. Do not reopen or modify them casually; a correction must be explicitly authorized and documented without silently changing their accepted behavior.
- Implement only the phase explicitly authorized by the active master prompt, and follow that phase's approved architecture, canonical specification, local ticket graph, test/review requirements, and acceptance gate.
- Do not scaffold or implement a later phase before it is authorized. Do not create placeholder modules, schemas, APIs, dependencies, or infrastructure for future phases.
- Use `/to-spec`, `/to-tickets`, `/implement-spec`, and related implementation workflows only when the active prompt authorizes them for the current phase. Planning artifacts and tickets default to local Markdown under `.scratch/`; external issue tracking remains subject to the external-mutation policy above.
- Local Git branches, worktrees, commits, and merges are allowed when required by an authorized phase workflow. Remote Git operations remain prohibited unless the human explicitly authorizes that exact operation under the external-mutation policy.
- Volume I, Volume II, Volume III, and Volume IV are complete and frozen. Phase 8–12 implementations are frozen; implementation authorization remains phase-specific and does not authorize later phases early. Volume IV architecture and Phase 11/12 specifications are frozen. Volume V architecture and Phase 13/14 specifications are frozen; Phase 13 implementation is authorized, while Phase 14 implementation is not authorized.
- Phases 8–12 have passed their acceptance gates and are locally frozen. Volume III integrated evidence is recorded in `docs/reviews/volume-3-acceptance.md`; its local freeze commit is `4e5fa3c3843591ed59c0a87721c138484b8feefa`. Volume IV evidence is recorded in `docs/reviews/volume-4-acceptance.md`; Phase 11/12 evidence is recorded in their acceptance records. Volume V is design-frozen and not complete; Phase 14 remains specification-frozen and implementation-not-authorized.
- When no phase is explicitly authorized by the active master prompt, do not advance implementation, generate phase tickets, or start a later phase. Stop and request direction if the authorized scope cannot be determined.
- Keep planning trackers local. Never create external issues without explicit authorization for that exact operation.

## Documentation discipline

Treat project-owner working decisions as constraints to document and stress-test, not silently replace. Record material counterarguments, unresolved questions, and safe assumptions in the architecture documentation and ADRs. Keep domain terminology consistent across product, workflow, data-ownership, and bounded-context documents. Future agents must read and follow this policy before acting.

## Agent skills

### Issue tracker

Planning and decision records use local Markdown under `.scratch/`; no external tracker writes are allowed. See `docs/agents/issue-tracker.md`.

### Triage labels

Use the five default local triage roles when a future planning workflow needs them. See `docs/agents/triage-labels.md`.

### Domain docs

Single product domain: `GLOSSARY.md` for terms, `CONTEXT.md` for higher-level domain context, and `docs/adr/` for durable architecture decisions. Read `docs/architecture/constitution.md` and the relevant architecture documents before later specifications or implementation. See `docs/agents/domain.md`.

