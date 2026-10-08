# ADR 0021: Identity & Access administers registered authorities at declared scope

- **Status:** Accepted
- **Date:** 2026-10-08
- **Decision owners:** AyuVahak architecture / product owner

## Context

Phase 2's initial grant API provisions the three platform authorities needed to operate the kernel. Later business contexts own and register their functional authorities, while authorization grants remain solely owned by Identity & Access. Phase 3 requires Directory permissions at exact Organization and Facility scopes. A platform-only grant API would either make those permissions impossible to provision, encourage direct edits to Identity & Access tables, or force overbroad platform grants. The existing authorization model and migration already distinguish platform, organization, and facility scopes, but the grant administration path had not yet exposed those target scopes.

## Decision

Keep grant records, authorization evaluation, mutation audit, idempotency receipts, and grant lifecycle with Identity & Access. An administrator must execute grant administration in validated `PLATFORM` context with `AccessGrantAdministration`. The command carries a separate explicit target scope. Identity & Access may create a grant only for an authority currently registered by its owning context and only at a scope kind declared by that authority.

For an Organization or Facility target, the owner of those facts validates that the referenced record is active and that a Facility belongs to the supplied Organization. For Phase 3 that owner is Directory. The caller cannot establish the association by supplying identifiers, and Directory never reads or writes Identity & Access grant storage. Revocation remains available by grant reference even if an authority is no longer registered, because revocation only removes access.

All previous protections remain: the administrator is authorized at platform scope; the administrator cannot grant to or change their own principal; trusted principal and service-client validation remains in force; every mutation is atomic with required audit and outbox intent; retries use Identity & Access-owned idempotency receipts; events contain only minimal grant and target-scope metadata; and failure is closed. Existing Phase 2 grants remain platform-scoped. No role claim, local job title, or caller-selected scope grants authority by itself.

## Consequences

- Business contexts can introduce and own scoped authorities without owning grants or depending on Identity & Access tables.
- The Identity & Access administration boundary gains a target-scope input distinct from its administrator execution scope; the normalized target is included in the request fingerprint, audit target context, and grant event metadata.
- The owning context must provide a narrow reference-validation capability before scoped grants for its resources can be provisioned. If validation is unavailable or ambiguous, creation fails without writing a grant.
- A deployment still maps local roles/teams/processes to functional authorities explicitly. No global role-to-authority mapping is introduced.

## Alternatives considered

- **Grant platform-wide Directory permissions:** rejected because it violates exact organization/facility authorization and least privilege.
- **Let Directory query or edit grant tables:** rejected because it bypasses Identity & Access ownership and its audit/idempotency transaction.
- **Add a Directory-owned grant system:** rejected because it duplicates authorization state and creates inconsistent revocation behavior.
- **Keep the Phase 2 endpoint platform-only:** rejected because Phase 3's approved scope model could not be operated without manual database mutation.

## References

- [Phase 2 platform-kernel architecture](../architecture/phase-2-platform-kernel.md)
- [Phase 2 specification](../specs/phase-2-platform-kernel-spec.md)
- [Phase 3 Directory specification](../specs/phase-3-care-service-directory-spec.md)
- [ADR 0011](0011-functional-receiving-authorities.md)
- [ADR 0012](0012-audit-capture-and-failure-policy.md)
- [ADR 0013](0013-idempotency-and-consumer-deduplication.md)
