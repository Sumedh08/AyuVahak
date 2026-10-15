---
status: accepted
date: 2026-10-15
---

# ADR 0023: Referral owns authorized, transient patient-context exchange

## Context

The frozen Referral stores a source-scoped opaque `PatientReference` and does not own a clinical chart. Volume III may need a selected receiving participant to obtain a minimum pre-arrival summary from an authoritative clinical source. Treating IPS as a durable AyuVahak patient document would create clinical-record retention, disclosure, identity-matching, and breach obligations beyond the coordination product. Returning data because a user belongs to the destination organization would also overgrant access.

## Decision

Referral owns the patient-context source port, disclosure policy decision, external identifier association, FHIR/IPS mapping, and minimal response. Transfer must first establish that the requester is a currently authorized participant in the selected active Transfer attempt. Referral independently checks the source-scoped PatientReference, purpose, and configured legal-basis/consent policy hooks. Required sensitive-read audit is durable before disclosure; if policy or required audit is unavailable, disclosure is denied.

The full IPS Bundle/Composition/Patient resource graph is transient processing input/output and is not cached or persisted by default. Referral may return only an explicit allowlist needed for the current coordination purpose. The external clinical system remains authoritative. No PatientReference, summary payload, diagnosis, or attachment is copied into Matching, Capacity, Transfer, Transport, events, generic audit, ordinary logs, metrics, or traces. The architecture does not set universal consent law or invent emergency break-glass access.

## Consequences

- IPS validity is not proof of patient identity, completeness, freshness, legal authority, or authorization.
- Source outage yields explicit unavailable/partial context and does not reverse a previously committed Transfer acceptance.
- Any future durable summary, cache, attachment, or derived patient field requires a separate purpose, minimization, access, expiry/deletion, and audit decision.
- Local tests use synthetic resources and local mock sources only.

## Alternatives considered

- **Persist full IPS for convenience:** rejected; it would make AyuVahak a clinical summary store and enlarge exposure without a defined coordination requirement.
- **Let Transfer own the PatientReference and fetch directly:** rejected; Transfer owns recipient participation and movement, while Referral owns the patient reference and its disclosure decision.
- **Destination organization membership implies access:** rejected; membership does not prove selected-case participation or satisfy purpose-specific policy.

## References

- [ADR 0001 — Coordination state, not clinical record](0001-coordination-state-not-clinical-record.md)
- [ADR 0004 — FHIR at interoperability boundaries](0004-fhir-at-interoperability-boundaries.md)
- [ADR 0010 — Domain contexts own integration ports](0010-domain-owned-integration-ports.md)
- [Phase 8 specification](../specs/phase-8-healthcare-standards-interoperability-spec.md)
- [Security and trust](../architecture/security-and-trust.md)
