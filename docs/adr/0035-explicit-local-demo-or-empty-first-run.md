# ADR 0035: Explicit local Demo or Empty first-run

## Status

Accepted for the authorized Volume V productization work.

## Context

A downloadable local package needs to be immediately understandable while remaining safe for deployments that do not want sample business state. Automatically seeding synthetic organizations or workflows can be mistaken for live data and can contaminate an empty deployment. The production platform already has an operator-controlled one-shot first-grant bootstrap and external identity boundary.

## Decision

The local package starts in `UNCONFIGURED` mode and its launcher requires an explicit, one-time operator choice of `SYNTHETIC_DEMO` or `EMPTY` before services start. The launcher writes a local mode marker and dedicated Compose project/volume identity. Application startup fails closed if a local mode is missing or invalid. There is no unauthenticated web mode-selection or grant-bootstrap endpoint. Demo reseed/reset is a local operator command that verifies the selected mode and exact dedicated disposable Demo database/volume, requires destructive confirmation, and drops that whole isolated store; it does not issue cross-context row deletes. It is excluded from production profiles. Demo fixtures use a versioned, unmistakably synthetic manifest and owner-approved commands. Empty mode has no synthetic business records. First application access uses the existing external IdP; the operator creates/maps an identity and runs the existing one-shot local grant bootstrap CLI out of band. The UI guides but cannot invoke that process and does not create production credentials or identity accounts.

## Consequences

- A clean-install test must cover both modes from separate disposable databases and assert reset refuses a non-Demo or non-dedicated target.
- Local browser surfaces validate Host/Origin and use configured OIDC PKCE; loopback is defense in depth rather than browser-request authorization.
- Demo reset cannot be exposed as a general application or production API.
- A demo banner must be present in the authenticated application shell on every demo route.
- Local disposable credentials may be generated for the operator's installation, but no reusable default password or secret is committed.
- Institutional deployments remain governed by Phase 13 profiles and are not transformed by local first-run behavior.
