# ADR 0034: Same-origin role-aware presentation client

## Status

Accepted for the authorized Volume V productization work.

## Context

The frozen platform is an API-first Spring Boot modular monolith with external OIDC authentication, server-side scoped grants, and owner APIs. It has no end-user client. A web client can improve operational usability, but an independently implemented role system, duplicated workflows, or direct persistence access would weaken the existing authority boundaries. A separate UI origin also adds CORS and deployment complexity to a local downloadable product.

## Decision

Add a React + strict TypeScript + Vite presentation client and package its production assets with AyuVahak so the supported distribution serves UI and API from one origin. The client authenticates through the configured external OIDC provider using Authorization Code with PKCE as a public client without a client secret. It sends only the access token to APIs and keeps tokens in the client library's in-memory/session handling; it does not use an ID token as an API credential or persist bearer tokens in local storage. It receives a minimal server-evaluated subject/capability/scope projection for presentation and invokes existing owner APIs for behavior. Backend authorization remains mandatory for every deep link and request. User-friendly templates only expand into existing granular authorities and organization/facility scopes.

The browser cannot access storage, private repositories, owner ports, or cross-context data. Any missing UI contract is implemented as a narrow API in the owning context. No patient-facing UI is included.

## Consequences

- Same-origin packaging keeps production CORS narrow and avoids runtime CDN requirements.
- The authenticated capability projection improves navigation but is not a credential or authorization token.
- UI integration tests must attempt unauthorized direct API/deep-link access.
- The server remains usable without the UI and the UI remains presentation-only.
- Development may use a local Vite proxy; it must not become a production origin dependency.
