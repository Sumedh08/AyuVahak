# Phase 13 application security evidence matrix

**Basis:** OWASP ASVS 5.0.0 (stable release, 2025-05), pinned requirement identifiers.
**Scope:** API-oriented AyuVahak application and its local production configuration. This is a
scoped engineering evidence map, not an ASVS verification-level selection, independent audit,
certification, or claim of full conformance. The official source recommends version-qualified
identifiers because requirement IDs can change between ASVS versions.

Status is conservative: **PARTIAL** means repository tests or design evidence cover a relevant
part but an independent verification/deployment evidence remains; **DEPLOYMENT-SPECIFIC** means
an operator must prove the control for its IdP/ingress/topology; **NOT ASSESSED** means this
repository has not established evidence. A standard reference alone never counts as evidence.

| ASVS 5.0.0 requirement | Applicability and evidence checked | Current assessment | Remaining evidence / limitation |
|---|---|---|---|
| `v5.0.0-V2.2.1` | Business/security input validation. Evidence: controller/domain validation tests, `ProductionRequestAbuseFilterTest`, and owner API acceptance suites. | PARTIAL | This is not a complete parameter-by-parameter inventory or an independent validation assessment. |
| `v5.0.0-V2.3.1` | Sequential business-flow enforcement. Evidence: Transfer/Referral/Transport state-machine and command acceptance tests; frozen owner specifications. | PARTIAL | Must keep each changed workflow covered by its owner-specific transition tests and deployment review. |
| `v5.0.0-V2.3.2` | Documented business-logic limits. Evidence: bounded request limits and production configuration validation tests; deployment profile docs. | PARTIAL | Workload-derived thresholds and aggregate gateway limits are deployment-specific; application limits alone do not establish capacity. |
| `v5.0.0-V3.4.2` | CORS allowlist behavior. Evidence: production profile validation and CORS configuration tests in `ProductionProfileConfigurationTest`. | PARTIAL | Deployed ingress/proxy behavior must be tested with the actual origin and credential policy. |
| `v5.0.0-V3.4.4` | `X-Content-Type-Options: nosniff`. Evidence: API response-header tests and the local restored Directory response in `BackupRestoreAcceptanceIT`. | PARTIAL | Verify every served response class and the final ingress behavior. This row does not claim a browser UI exists. |
| `v5.0.0-V8.2.1` | Function-level permission. Evidence: identity/grant application boundary, protected endpoint tests, and negative authorization tests. | PARTIAL | This map is not an exhaustive endpoint-to-authority review. |
| `v5.0.0-V8.2.2` | Data-specific authorization. Evidence: organization/resource-scope tests across owner API integration suites. | PARTIAL | A full route/resource authorization inventory and live deployment grant review remain required. |
| `v5.0.0-V8.3.1` | Trusted service-layer authorization. Evidence: owner application contracts and tests that exercise server-side grant checks; no client-only policy is treated as authority. | PARTIAL | Independently review every new adapter/command path and generated API surface. |
| `v5.0.0-V9.1.1` | Self-contained-token integrity. Evidence: OIDC JWT signature, issuer, audience and expiry validation tests against local signing fixtures. | PARTIAL | Key-provider lifecycle, issuer metadata and production key rotation require deployment evidence. |
| `v5.0.0-V6.3.1` | Password credential-stuffing/brute-force control. The app delegates user authentication to its configured OIDC provider; it does not own passwords. | DEPLOYMENT-SPECIFIC | The selected IdP must demonstrate its applicable authentication-abuse controls. Do not infer them from AyuVahak's resource-server tests. |

## Explicitly scoped out of this API-only assessment

Browser document execution controls such as CSP and browser session/cookie requirements are
not declared satisfied. If a human-facing web client is introduced, create a UI-specific ASVS
scope and verify the actual document origin, CSP, framing, cookies, CSRF, accessibility and
browser session behavior. File-upload controls are not claimed unless an upload surface is
introduced. OIDC-provider controls remain the responsibility of the selected and configured
provider, with AyuVahak verifying its own relying-party behavior.

## Review and maintenance

Before a release, re-evaluate the applicable stable ASVS version, update each evidence link to
the exact test/report/revision, and independently review rows marked PARTIAL or
DEPLOYMENT-SPECIFIC. Any exclusions require a surface-specific rationale. This matrix does not
claim an ASVS level or certification.

**Official basis:** [OWASP ASVS project and stable 5.0.0 release](https://github.com/OWASP/ASVS),
including its [version-qualified reference guidance](https://github.com/OWASP/ASVS/blob/master/5.0/en/0x03-What-is-the-ASVS.md).
