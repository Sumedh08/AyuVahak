---
status: accepted
date: 2026-10-08
---

# Provision the first AyuVahak grant through a one-shot local operator command

## Context

AyuVahak owns its application authorization grants, but no authenticated principal has a grant when a new deployment starts. An HTTP bootstrap route, shared default application administrator, or automatic trust of an IdP administrator role would create a permanent or easily exposed privilege path. Direct database edits would bypass the application's audit boundary and make the bootstrap action difficult to reproduce.

The deployment operator must establish the first grant before normal grant-administration operations can be authorized. The operator is trusted to control deployment host and database access; AyuVahak does not authenticate that operator through the OIDC resource-server flow during the one-time bootstrap action.

## Decision

Use an explicit, one-shot local command to provision the initial platform-scoped `AccessGrantAdministration` authority for a named issuer-scoped grantee. The command:

- runs as a separate administrative process mode and does not start an HTTP listener;
- requires explicit operator and grantee references, issuer, confirmation, and platform scope;
- records the operator-supplied reference together with the local operating-system principal and database session principal when available. The reference itself is not independently verified by AyuVahak; the trust assumption is that deployment controls protect host/shell access and database credentials. The audit labels this as out-of-band host/DB control, not OIDC-authenticated application identity or cryptographic attestation;
- writes the initial grant, its required audit record, the `identity-access.grant-created.v1` outbox event, and a durable bootstrap-completed marker in one PostgreSQL transaction;
- refuses to run if a successful bootstrap marker or any grant state already exists, including after the initial grant is revoked;
- exits after success and exposes no network bootstrap endpoint, default AyuVahak administrator, shared application password, or persistent bypass.

Normal grant creation and revocation use the protected Identity & Access capability and ordinary AyuVahak authorization/audit rules. Recovery after loss of all grant administrators is a separately governed deployment database-recovery operation, not a reopening of the bootstrap command.

## Consequences

- A deployment must first create the intended human or service identity in its trusted IdP and protect operator access to the host and database.
- Bootstrap accountability is explicit but is only as strong as the deployment's host/DB operator identity and command auditing; the audit record must not claim IdP authentication that did not occur.
- A successful marker prevents an accidental second bootstrap even if all ordinary grants are later revoked.
- The command is local and short-lived, not part of normal application traffic, and can be exercised with synthetic identities in local tests.
- Initial access grants only grant-administration capability; it does not grant a clinical, referral, transfer, or other healthcare authority.

See the [Phase 2 platform-kernel architecture](../architecture/phase-2-platform-kernel.md) and [Phase 2 specification](../specs/phase-2-platform-kernel-spec.md).
