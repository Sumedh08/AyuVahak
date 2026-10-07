---
status: accepted
date: 2026-10-08
supersedes: 0009-separate-receiving-confirmations.md
---

# Receiving authority is a function mapped by local policy

## Context

The earlier baseline tied clinical suitability to a “receiving clinician” and operational readiness to a “receiving coordinator.” Health systems assign these responsibilities differently: a transfer-center nurse, bed manager, specialty registrar, medical officer, referral desk, command center, team, or governed local process may hold one or more decision rights. A universal job-title assumption would embed one organizational model into the domain and authorization policy.

## Decision

Preserve three separate receiving-side decision functions:

1. **Clinical Suitability Authority** makes or attests to the Clinical Suitability Assessment for the stated Care Requirement.
2. **Operational Readiness Authority** makes or attests to the Operational Readiness Assessment for current capacity and operational conditions.
3. **Acceptance Authority** records facility willingness to proceed only after both required assessments are positive.

Each deployment maps the functions to its own roles, teams, or explicitly governed automated process and configures authorization accordingly. One person may perform multiple functions only when local policy explicitly grants those rights and the resulting facts remain separately attributed. Automation may record a decision only where an accountable deployment policy explicitly authorizes it; AyuVahak does not infer suitability/readiness or autonomously accept.

Acceptance remains separate from Reservation, which remains separate from Transfer and Transport. Authentication establishes the subject; Identity & Access evaluates local organization, action, context, and purpose policy.

## Consequences

- Domain terms describe authority functions rather than a globally required job title.
- Actor, context, organization, action, target, event/recorded time, outcome, and correlation are attributable for each assessment and Acceptance.
- Scope boundaries, workflow, data ownership, and quality scenarios use the same three decision functions.
- Deployments must define their role-to-function and permission mapping before live use. A missing or unverified authority is pending/unknown, never a positive decision.
- The distinction is preserved even when local actors combine functions; the functional records cannot be inferred from a single undifferentiated “accepted” signal.

See [domain context](../../CONTEXT.md), [glossary](../../GLOSSARY.md), [workflow](../workflows/emergency-interfacility-transfer.md), [security and trust](../architecture/security-and-trust.md), and [ADR 0009](0009-separate-receiving-confirmations.md) for the historical decision.
