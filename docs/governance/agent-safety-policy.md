# Agent Safety Policy

This document explains the permanent operating rule in [AGENTS.md](../../AGENTS.md). Agents and automated tools may help design or maintain AyuVahak, but their convenience does not grant authority over a health system, a shared repository, or a patient's information.

## Why the policy exists

AyuVahak may exchange information with clinical systems, identity providers, facility registries, public-health services, government infrastructure, and partner repositories. A mistaken write can create false referrals, alter operational capacity, disclose sensitive information, trigger downstream care actions, or change shared software and infrastructure. “Demo,” “sandbox,” “test,” and “development” labels do not establish that a system is disposable or that a write is authorized.

Therefore external state changes are forbidden by default. Any later external mutation requires explicit human authorization for that exact class of operation. If it is unclear whether an action changes remote state, treat it as a mutation and stop. An inaccessible system is not a reason to bypass its controls.

## Safe research and testing defaults

Use read-only official documentation and source material for research. Do not authenticate to healthcare infrastructure, submit forms, create accounts, probe systems, bypass controls, or write to public/shared healthcare servers. Never use live public FHIR, DHIS2, OpenMRS, OpenHIE/OpenHIM, OpenLMIS, government, or other public/shared healthcare systems as integration targets.

Future integration verification defaults to:

- local containers and services controlled by the developer;
- deterministic synthetic fixtures and static standards examples;
- local mock/stub servers and recorded test responses that contain no real patient information;
- disposable infrastructure created and owned by the AyuVahak developer or test environment.

Never use real PHI/PII or production credentials in fixtures, examples, logs, recordings, screenshots, or repositories. Use synthetic identifiers. Do not request, generate, commit, or transmit production passwords, API keys, OAuth credentials, private keys, healthcare-system credentials, or cloud secrets.

## Git and shared project state

Local reads and edits are permitted. During Phase 0, do not push, create remote branches, create issues/PRs/releases, change repository settings, or deliberately trigger remote workflows. Across all phases, the external-mutation rule in `AGENTS.md` means public/open-source upstream repositories and shared project systems are read-only unless a human explicitly authorizes the exact write operation. A local branch or draft file does not authorize publication.

Do not create external tracker items when a local Markdown tracker is sufficient. The current local planning convention is described in [docs/agents/issue-tracker.md](../agents/issue-tracker.md).

## Requirements for future test environments

Before an integration test runs, the test must make its target explicit and verify that the target is local or disposable infrastructure controlled by the developer. Test configuration should fail closed when the target is missing or points outside the approved local environment. A test must not fall back to a public demo or partner service. Test datasets must be synthetic. If a workflow needs external behavior, use a local mock or a containerized instance with deterministic setup and teardown.

Any future need to write to a non-local system must be treated as a separate human-authorized operational action with an identified owner, target, data class, expected effects, rollback/recovery plan, and narrow scope. This does not grant standing permission for later agents or tests.

## Scope

These rules apply to research, planning, implementation, testing, deployment, maintenance, and agent automation. They do not replace jurisdictional privacy, clinical safety, security, or operational governance; those controls must be established by the accountable deployment before live use.
