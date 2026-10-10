# ADR 0022: Matching owner reads use a dedicated scoped service identity

- **Status:** Accepted
- **Date:** 2026-10-10
- **Decision owners:** AyuVahak architecture / product owner

## Context

Generating a recommendation requires a human request authorized at the Referral's origin scope and owner facts from Referral, Directory, and many possible destination Facilities. The requester is not necessarily authorized to read every candidate Facility's Capacity evidence. Forwarding the requester's rights would turn an origin permission into cross-facility access and violate the rule that machine access does not inherit human authority. Direct reads of other contexts' persistence would violate context ownership.

## Decision

Matching requires an explicit human caller with the Matching-owned `RecommendationRequest` authority at the validated origin scope. It performs Referral, Directory, and Capacity reads through a separate deployment-configured machine identity. The machine identity is authenticated and attributed as itself; it never impersonates the human, forwards a human token, or inherits human grants.

The machine identity receives only owner-held read authorities required by deployment configuration:

- `ReferralRead` at participating origin scopes, only for the minimal submitted-requirement contract;
- `DirectoryRead` within the deployment-approved candidate search boundary. When represented by multiple organization-scope grants, Matching queries each authorized organization through the Directory owner and merges the bounded results deterministically. A platform-scope grant is allowed only when the Directory instance itself is limited to that deployment boundary;
- `CapacityEvidenceReview` at each participating destination Facility scope.

Matching policy and requests still carry explicit geographic and candidate-organization bounds. The provider context validates the caller's grant and target scope. Missing, revoked, ambiguous, or unavailable authorization fails closed and cannot produce a partial positive recommendation. The identity has no write permissions in Referral, Directory, or Capacity.

The initiating human actor and validated origin scope are retained in Matching command audit and snapshot attribution. Each owner records its own read as the machine actor with the target scope, action, `MATCHING_EVALUATION` purpose, outcome, and shared correlation identifier. The Referral contract returns only required capabilities, non-patient constraints, origin, and exact revision/status; it excludes `PatientReference`, demographics, narrative, and full Referral state. Directory and Capacity return bounded owner-produced evidence.

## Consequences

- Origin-scoped human authority cannot silently expand into cross-facility reads.
- Each provider remains responsible for authorization, audit, freshness, and evidence semantics.
- Deployments must explicitly provision the machine identity's read grants for the origins and candidate Facilities they participate in; new organizations or Facilities do not acquire access automatically.
- Missing grants can make evaluation unavailable or indeterminate, reducing recommendation availability without creating false positive eligibility.
- Matching owns no external-context tables and stores only minimal evidence references/facts under its existing snapshot policy.

## Alternatives considered

- **Forward the human requester's token or grants:** rejected because the origin authority does not imply permission to read every destination and would make the human's identity an unsafe proxy.
- **Grant broad platform access by default:** rejected because it silently widens access across every context and deployment.
- **Read provider tables directly:** rejected because it bypasses owner authorization, audit, and projection semantics.
- **Require the human to hold read access at every candidate Facility:** rejected because a referral coordinator's origin role should not imply destination-side access; explicit service grants make the cross-context purpose attributable and manageable.

## References

- [Security and trust assumptions](../architecture/security-and-trust.md)
- [Volume II context contracts](../architecture/volume-2-context-contracts.md)
- [Matching policy v1](../architecture/matching-policy-v1.md)
- [Phase 6 specification](../specs/phase-6-matching-recommendation-spec.md)
- [ADR 0005](0005-external-identity-and-local-authorization.md)
