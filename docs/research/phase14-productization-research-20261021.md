# Phase 14 Productization Research

**Retrieved:** 2026-10-21 (UTC)
**Scope:** Frontend/runtime choices, browser testing, accessibility, Spring Boot static-asset serving, and bounded HTTP/SSRF controls for the authorized productization addendum.
**Method:** Read-only review of project/vendor documentation, standards, release pages, and package metadata. No service was mutated, no healthcare endpoint was contacted, and no real model provider was used.

## Findings at a glance

| Component | Current stable evidence on retrieval date | Recommended initial pin | Important qualification |
|---|---|---|---|
| React | React 19.3 announced stable; upstream release and registry `latest` resolve to 19.3.1 | `react` and `react-dom` 19.3.1, verifying both exact package versions in the lockfile | React release does not select the rest of the frontend architecture. |
| TypeScript | TypeScript 7.0 is stable; registry `latest` is 7.0.2. The TypeScript team says 7.0 does not yet expose a stable programmatic API. | Start with 6.0.3 if selected lint/editor tooling depends on the compiler API; test 7.0.2 as a separate toolchain decision. | “Latest” does not mean every ecosystem integration has caught up. |
| Vite | Current supported regular-fix line is 8.3; upstream release is 8.3.4. | Vite 8.3.4 | Pin the exact patch in the lockfile; review Vite migrations on later major updates. |
| Node.js | Node 24 is LTS; the official distribution index lists 24.21.0 on 2026-09-07. Node 26 is Current, not LTS, on the retrieval date. | Node 24.21.0 for the development/build toolchain | Node is a build/test prerequisite; the packaged static UI need not require Node at runtime. |
| Playwright Test | Official release notes document 1.64; package metadata `latest` resolves to 1.64.1 and declares Node `>=20`. | `@playwright/test` 1.64.1 | Keep package and browser binaries version-matched; local browser tests do not certify accessibility or security. |
| Accessibility | WCAG 2.2 is a W3C Recommendation dated 2024-12-12. | Use WCAG 2.2 Level AA as the product acceptance target | A target or automated scan is not itself a conformance claim. |

These are a dated compatibility snapshot, not floating dependency instructions. Use exact package versions and a checked-in lockfile. Recheck official release/support status immediately before installing dependencies for implementation.

## 1. Frontend toolchain

### React

The React team announced React 19.3 as available on npm on 2026-09-09, with selected new APIs declared stable in that release. The upstream release list and package registry’s `react/latest` metadata resolved to 19.3.1 on retrieval. Use the same exact release for `react` and `react-dom`; verify both package versions in the eventual lockfile. Sources: [React 19.3 release announcement](https://react.dev/blog/2026/09/09/react-19-3), [React 19.3.1 upstream release](https://github.com/react/react/releases/tag/v19.3.1), [React package metadata](https://registry.npmjs.org/react/latest), [React DOM package metadata](https://registry.npmjs.org/react-dom/latest).

The React project’s 2026-10-14 security advisory lists React Server Components packages affected through 19.3.0 and patched in 19.3.1; it also says a client-only application without a framework, bundler, or plugin that supports React Server Components is not affected by that advisory. Source: [React Server Components security advisory](https://github.com/react/react/security/advisories/GHSA-xqc2-xf4c-x3h3).

**Does not imply:** React 19.3 is required for the AyuVahak UI, that new animation/server features should be used, or that React supplies authorization, workflow semantics, or accessible interaction automatically.

**AyuVahak decision:** React is a suitable presentation layer for the requested role-aware shell and workspaces. Pin the patched 19.3.1 release and do not add React Server Components or server-component framework packages to this client-only SPA. Keep rendering and navigation separate from authoritative operations. Load all coordination facts and execute actions through authenticated backend APIs; keep role/capability filtering ergonomic only, with server-side grants and scopes remaining authoritative.

### TypeScript

Microsoft announced TypeScript 7.0 as stable on 2026-07-08, and the package registry’s `typescript/latest` metadata resolved to 7.0.2. The TypeScript team also states that 7.0 does not yet provide a stable programmatic API, and explains that tools embedding the compiler/language services may need to remain on TypeScript 6.0 for the moment. The official releases page lists 6.0.3 as well. Sources: [Announcing TypeScript 7.0](https://devblogs.microsoft.com/typescript/announcing-typescript-7-0/), [TypeScript package metadata](https://registry.npmjs.org/typescript/latest), [TypeScript releases](https://github.com/microsoft/TypeScript/releases).

**Does not imply:** TypeScript 7.0.2 is unsuitable for all applications, or that 6.0.3 is the newest stable compiler. It means compatibility should be proved for the actual lint, test, editor, and build toolchain rather than inferred from the compiler’s stable label.

**AyuVahak decision:** Use strict compiler settings and exact lockfile pins. Before implementation selects TypeScript 7.0.2, verify the chosen parser/linter/editor tooling against it. If an integrated tool requires the stable compiler API, use the exact TypeScript 6.0.3 release initially and record the reason, while keeping the compiler strict. Revisit 7.x after its API and selected integrations are verified. Do not use `any` as a compatibility workaround.

### Vite and Node.js

Vite’s official release policy lists 8.3 as the regular-patch line and describes its support/backport bands. Its 8.3.4 release was published on 2026-10-08. Vite’s package manifest requires Node `^20.19.0 || >=22.12.0`, which includes Node 24. Sources: [Vite release policy](https://vite.dev/releases), [Vite 8.3.4 release](https://github.com/vitejs/vite/releases/tag/v8.3.4), [Vite 8.3.4 package manifest](https://github.com/vitejs/vite/blob/v8.3.4/packages/vite/package.json).

The Node project says production deployments should use Active or Maintenance LTS releases. Its release page classified Node 24 as LTS and Node 26 as Current on 2026-10-21; its official distribution index lists Node 24.21.0 dated 2026-09-07. Sources: [Node.js release lines](https://nodejs.org/en/about/previous-releases), [official Node.js distribution index](https://nodejs.org/dist/index.json).

**Does not imply:** Vite is a runtime server, a particular cloud/static host is required, or using an LTS runtime makes dependencies secure. Vite’s docs say `vite build` emits optimized static assets suitable for static hosting; Node is needed to build/test, not to serve the resulting browser assets. Source: [Vite production build](https://vite.dev/guide/build).

**AyuVahak decision:** Build React/TypeScript with Vite 8.3.4 using Node 24.21.0, with package versions pinned in the lockfile and Node recorded in a repository version file and CI. Produce local static assets as an ordinary build output. Do not introduce a Node production service just to serve the SPA.

### Playwright

Playwright’s release notes list 1.64 as the current release family; `@playwright/test/latest` resolved to 1.64.1 in the official npm registry metadata and requires Node `>=20`. Each Playwright release documents the corresponding browser builds; the browser guide recommends keeping Playwright updated to test against its current browser revisions. Sources: [Playwright release notes](https://playwright.dev/docs/release-notes), [Playwright Test package metadata](https://registry.npmjs.org/@playwright/test/latest), [Playwright browser documentation](https://playwright.dev/docs/browsers).

**Does not imply:** E2E coverage proves conformance, clinical safety, or all browser compatibility. Playwright only automates the behaviors and browser builds actually tested.

**AyuVahak decision:** Pin `@playwright/test` 1.64.1 in the frontend lockfile; install browsers from the matching Playwright revision in CI and local acceptance. Run only against loopback/local AyuVahak and synthetic fixtures. Include fresh Demo and Empty installation flows, role/scope denial, direct API denial, cross-organization isolation, disabled intelligence, and deterministic fake-provider cases.

## 2. Same-origin static UI and backend authority

Spring Boot 4.1.1 documents that Spring MVC serves static content from classpath `/static`, `/public`, `/resources`, or `/META-INF/resources`; it automatically uses an `index.html` at a configured static location as a welcome page. The reference also warns that default static mapping is `/**`, so a handler can catch paths beyond the UI’s root. Sources: [Spring Boot Servlet Web Applications: Static Content and Welcome Page](https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.spring-mvc.static-content), [Spring Boot 4.1.1 reference](https://docs.spring.io/spring-boot/reference/).

**Does not imply:** Spring’s default welcome-page behavior implements all SPA client-side routes, secures an API, or makes every unknown URL safe to forward to `index.html`.

**AyuVahak decision:** Prefer Vite production output packaged under the existing Spring Boot application’s classpath static directory. This gives a same-origin UI/API distribution and avoids adding production CORS complexity or a separate Node service. Add an explicit SPA route fallback only for known UI paths; exclude `/api/**`, authentication/callback paths, management endpoints, and static-asset misses. Test deep-link refresh and API 404 behavior. Continue to authenticate and authorize every API operation on the server. Never let frontend state, route visibility, or client-supplied organization scope substitute for backend authorization.

Same origin reduces cross-origin setup but does not establish CSRF protection, safe token storage, secure cookie policy, or permission to omit server-side access checks. Those remain implementation and regression-test obligations.

## 3. Accessibility basis

WCAG 2.2 is a W3C Recommendation (2024-12-12). It provides technology-neutral, testable success criteria and addresses web content across device types; W3C explicitly notes that it will not address every accessibility need. Level AA means the page satisfies all Level A and Level AA criteria; conformance applies to full pages and complete processes. Sources: [WCAG 2.2 Recommendation](https://www.w3.org/TR/WCAG22/), [conformance requirements](https://www.w3.org/TR/WCAG22/#conformance), [full pages](https://www.w3.org/TR/WCAG22/#full-pages), [complete processes](https://www.w3.org/TR/WCAG22/#complete-processes).

Two directly relevant Level AA criteria are that keyboard focus is not entirely hidden by author-created content ([2.4.11 Focus Not Obscured (Minimum)](https://www.w3.org/TR/WCAG22/#focus-not-obscured-minimum)) and that pointer targets are at least 24 by 24 CSS pixels except for defined exceptions ([2.5.8 Target Size (Minimum)](https://www.w3.org/TR/WCAG22/#target-size-minimum)). WCAG conformance claims are optional; if made, they have required scope, date, version, level, and technology details ([conformance claims](https://www.w3.org/TR/WCAG22/#conformance-claims)).

**Does not imply:** A target of WCAG 2.2 AA is legal advice, certification, proof that all user needs are covered, or a pass from an automated checker alone.

**AyuVahak decision:** Use WCAG 2.2 AA as the acceptance target for the complete UI journeys, not merely the shell. Test semantic headings/forms/tables, keyboard-only operation, visible and unobscured focus, focus order, labels/error association, status announcements, contrast, non-color status meaning, responsive/reflow behavior, and the full first-run/bootstrap/workflow processes. Add automated checks as a regression aid, then perform manual keyboard and assistive-technology checks. Do not claim formal conformity unless the entire declared product scope has been evaluated and the claim is accurately scoped.

## 4. Provider-neutral HTTP and SSRF boundary

OWASP’s SSRF Prevention Cheat Sheet recommends allowlisting when the application communicates with known trusted destinations. It advises against accepting complete URLs from users; instead, match an allowed host and construct the URL from an application-controlled scheme, port, and path. It also warns that hostname allowlisting alone does not prevent DNS rebinding: validate resolved IPv4 and IPv6 destinations and make the HTTP client connect to the validated address without a second unchecked DNS lookup. Apply the same destination checks to retries and fallback paths. Source: [OWASP Server-Side Request Forgery Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html).

**Does not imply:** An allowlisted provider is trustworthy for every data class, its contractual retention/training behavior is acceptable, or an endpoint allowlist alone prevents all outbound abuse. The OWASP examples are guidance and must be translated to the actual HTTP client and network policy.

**AyuVahak decision:** The initial support capability accepts a finite question key, never an endpoint or arbitrary prompt. A separate administrator-controlled provider profile selects an approved provider destination and model profile. Reuse the repository’s outbound trust/security boundary where it is technically applicable; do not add a direct client-controlled URL path. Enforce scheme/host/port/path policy, destination address checks at connection time, TLS hostname verification, bounded connect/read/total timeout, request/response byte limits, redirect policy, and identical checks for retry/fallback. Store only a secret reference and operational status in ordinary application records, never secret readback. Keep the deterministic fake as the only automated acceptance provider; no external inference is needed. Treat provider data-retention/training terms and allowed data classes as explicit deployment review, not as a consequence of SSRF controls.

## 5. Implementation checklist derived from the sources

1. Use Vite as a build tool and package its production assets into the existing Spring Boot distribution; run no frontend build server in the production profile.
2. Pin exact React, React DOM, TypeScript, Vite, Node, and Playwright versions; update them deliberately with build, typecheck, unit, E2E, dependency and accessibility verification.
3. Resolve TypeScript 6/7 compatibility before implementation locks the compiler and lint tooling; do not equate a stable release label with ecosystem compatibility.
4. Keep the browser as a presentation client: backend APIs remain the source of identity, scope, authorization, data, and all business transitions.
5. Exercise complete workflows with Playwright against local synthetic installations, including unauthorized deep links and direct API calls.
6. Evaluate accessibility against WCAG 2.2 AA for the full screen/process scope using automated checks plus manual keyboard/assistive-technology review; report evidence without certification claims.
7. Keep LLM provider selection administrator-controlled and server-side, bounded by destination and data-class policy; test only a local fake/mock.

## 6. Browser OIDC client boundary

The `oidc-client-ts` documentation describes the browser `UserManager` flow for Authorization Code with PKCE, and its settings expose `disablePKCE` with PKCE enabled by default. Its `InMemoryWebStorage` implementation provides a `WebStorageStateStore` backend that does not persist the user record across page reloads. Sources: [oidc-client-ts project documentation](https://authts.github.io/oidc-client-ts/), [UserManagerSettings reference](https://authts.github.io/oidc-client-ts/interfaces/UserManagerSettings.html), [InMemoryWebStorage reference](https://authts.github.io/oidc-client-ts/classes/InMemoryWebStorage.html), [upstream releases](https://github.com/authts/oidc-client-ts/releases).

**Does not imply:** Keeping the user record in memory prevents script injection, protects a token from malicious same-origin code, or makes browser-held bearer tokens equivalent to an HttpOnly cookie. The OIDC protocol also needs temporary state and a PKCE verifier during redirect processing.

**AyuVahak decision:** Use the public-client Authorization Code flow with PKCE, no client secret, an in-memory OIDC user store, and session-scoped storage only for temporary redirect state. Disable automatic silent renewal and session monitoring in the initial shell. API calls receive only the current access token, never an ID token, and use the same origin. Keep workspace navigation inside the single-page application so navigation does not unnecessarily discard the memory-only user. Do not treat the shell or client token as authorization evidence; the backend `/api/v1/session` projection and each owner API remain authoritative.

## Source register

All URLs below are first-party project/standard sources or canonical package metadata as observed on 2026-10-21.

- [React 19.3 release announcement](https://react.dev/blog/2026/09/09/react-19-3), [React 19.3.1 upstream release](https://github.com/react/react/releases/tag/v19.3.1), [React Server Components security advisory](https://github.com/react/react/security/advisories/GHSA-xqc2-xf4c-x3h3), and [React](https://registry.npmjs.org/react/latest)/[React DOM](https://registry.npmjs.org/react-dom/latest) package metadata.
- [TypeScript 7.0 announcement](https://devblogs.microsoft.com/typescript/announcing-typescript-7-0/), [TypeScript latest package metadata](https://registry.npmjs.org/typescript/latest), and [TypeScript release list](https://github.com/microsoft/TypeScript/releases).
- [Vite release policy](https://vite.dev/releases), [Vite 8.3.4 release](https://github.com/vitejs/vite/releases/tag/v8.3.4), [Vite 8.3.4 manifest](https://github.com/vitejs/vite/blob/v8.3.4/packages/vite/package.json), [production build guide](https://vite.dev/guide/build).
- [Node.js release lines](https://nodejs.org/en/about/previous-releases) and [official distribution index](https://nodejs.org/dist/index.json).
- [Playwright release notes](https://playwright.dev/docs/release-notes), [Playwright Test latest package metadata](https://registry.npmjs.org/@playwright/test/latest), and [browser documentation](https://playwright.dev/docs/browsers).
- [W3C WCAG 2.2](https://www.w3.org/TR/WCAG22/).
- [Spring Boot 4.1.1 Servlet Web Applications](https://docs.spring.io/spring-boot/reference/web/servlet.html).
- [OWASP SSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html).
