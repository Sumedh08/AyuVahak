---
status: accepted
---

# Delegate authentication and keep application authorization local

Use an external identity provider for authentication; do not create home-grown username/password credentials. AyuVahak enforces application-specific role-based access initially, scoped by organization/facility and action, with a path to attribute-based rules when justified. SMART on FHIR can authorize compatible FHIR access but does not replace the app's full workflow policy. The distinction keeps identity proofing with an established authority while preserving local control over who may see or act on a coordination case.

**Amended:** 2026-10-08 for the Phase 2 identity and trust design. OIDC is the initial human authentication protocol; Keycloak is the local reference provider, while deployments may use compatible providers. AyuVahak is an API resource server and validates signed access tokens locally against exact configured issuer, audience, trusted signing keys, and valid time bounds. An ID Token is not an API access token. Human-client login/session design remains outside Phase 2. Machine clients use separately scoped client-credentials identities and are never treated as human actors. AyuVahak owns scoped RBAC-first grants over functional authorities; provider role claims do not alone authorize actions. Every action uses explicit, validated organization and optional facility context. Deployment mappings remain configurable. Exact provider/image/dependency versions are verified before implementing the corresponding adapter. See [Phase 2 architecture](../architecture/phase-2-platform-kernel.md), [specification](../specs/phase-2-platform-kernel-spec.md), and [primary-source identity research](../research/phase-2-identity-provider-research.md).
