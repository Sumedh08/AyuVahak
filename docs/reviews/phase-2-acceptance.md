# Phase 2 acceptance evidence

**Verification date:** 2026-10-08
**Environment:** Windows 11, Eclipse Temurin Java `25.0.4.1+1`, Docker Desktop, local disposable containers; synthetic data only.

## Integrated build

| Requirement | Verification | Result | Caveat |
| --- | --- | --- | --- |
| Compile, tests, architecture rules, formatting, package | `.\mvnw.cmd -B -ntp clean verify` | PASS — 86 unit tests, 55 integration tests; zero failures, errors, or skips. ArchUnit and Spotless completed; package produced. | First Keycloak container startup took about 112 seconds while Quarkus initialized; it completed successfully. |
| Dependency baseline / prohibited dependencies | `.\mvnw.cmd -B -ntp dependency:tree '-DoutputFile=target/phase2-dependency-tree.txt'`; searched dependency tree and production source paths. | PASS — expected Spring Security, Nimbus JOSE, validator, and OTel dependencies found. No JPA/ORM, HAPI/FHIR, DHIS2, broker, Redis, search, graph, AI, or workflow dependency found. No business/patient package found. | `hibernate-validator` is the Bean Validation implementation, not Hibernate ORM. The project artifact itself retains its local `0.1.0-SNAPSHOT` version; the dependency tree contains no snapshot dependency. |
| Diff whitespace | `git diff --check` | PASS. | Run again immediately before freeze commit. |

## Module acceptance evidence

| Module | Evidence command/test | Result | Remaining limitation |
| --- | --- | --- | --- |
| 2.1 Core primitives | Surefire `CorePrimitivesTest`, `ProvenanceTest`; ArchUnit test suite | PASS. | Only platform/kernel concepts exist; no business identifiers or healthcare examples. |
| 2.2 Execution context | `ExecutionContextTest`, `LocalBootstrapExecutionContextTest`, `AuthorizationDecisionIT` | PASS. | Organization references are checked against the explicit Phase 2 reference catalog; no facility registry is implemented. |
| 2.3 Authentication | `AuthenticationHttpIntegrationTest`, `TokenPrincipalResolverTest`, `KeycloakServiceTokenIT`, `AuditWriterIT` | PASS. Local synthetic service token was minted from Compose Keycloak; protected request reached authorization and received 403, not 401. | Provider compatibility beyond the local reference was not tested. Human access-token acquisition UI/client flow is outside this phase. |
| 2.4 Authorization | `AuthorizationServiceTest`, `AuthorizationDecisionIT`, `AccessGrantAdministrationIT` | PASS — grant scope, revocation, service/human principals, and cross-scope denial covered. | Deployment role/team mapping remains deployment-configurable. |
| 2.5 Audit | `AuditWriterIT`, `AuditReviewApiIT`, `AccessGrantAdministrationIT`, `AuthenticationHttpIntegrationTest` | PASS — mutation atomicity, sensitive-read protection, denial/security evidence, and audit failure semantics covered. | Retention durations remain deployment-governed; no jurisdictional retention claim is made. |
| 2.6 Provenance | `ProvenanceTest`, `ProvenanceOwnerFixtureTest`; ArchUnit ownership check | PASS. | No business fact source or provenance storage adapter is created before a business owner exists. |
| 2.7 Event contract | `EventEnvelopeJsonCodecTest`, `EventTypeTest`, `AggregatePositionTest` | PASS — explicit JSON, version, ordering position, correlation and causation semantics. | No external event contract or broker is included. |
| 2.8 Outbox/recovery | `OutboxTransactionPostgresIT`, `EventDeliveryOperationsIT` | PASS — real Postgres transaction, rollback, pending restart recovery, bounded retry/lease, ordering, independent aggregate progress, replay/no-handler and retention behavior. | The dispatcher is internal to the modular monolith; no production workload benchmark. |
| 2.9 Idempotency/dedup | `CommandIdempotencyValueTest`, `IdempotencyOwnerBoundaryIT`, `OutboxTransactionPostgresIT` | PASS — exact retry, concurrent command uniqueness, key misuse conflict, replay receipts, duplicate event handling. | Receipt/replay retention is bounded as specified; no external consumer adapter exists. |
| 2.10 Failure/recovery | Full integration suite plus `clean verify` | PASS — local failure injection and recovery all green. | No public or production system was involved. |

## Local Compose smoke

Commands executed:

```powershell
docker compose --env-file .env.example config --quiet
docker compose --env-file .env.example up --build --detach
curl.exe -sS --max-time 12 -o NUL -w '%{http_code}' http://127.0.0.1:18080/actuator/health
curl.exe -sS --max-time 12 -o NUL -w '%{http_code}' http://127.0.0.1:18080/actuator/health/liveness
curl.exe -sS --max-time 12 -o NUL -w '%{http_code}' http://127.0.0.1:18080/actuator/health/readiness
curl.exe -sS --max-time 12 -o NUL -w '%{http_code}' http://127.0.0.1:18080/api/v1/system-info
curl.exe -sS --max-time 12 -o NUL -w '%{http_code}' http://127.0.0.1:18080/actuator/prometheus
curl.exe -sS --max-time 12 -o NUL -w '%{http_code}' http://127.0.0.1:18081/realms/ayuvahak/.well-known/openid-configuration
docker compose --env-file .env.example down
```

| Check | Result |
| --- | --- |
| Compose configuration validation | PASS. |
| Image build and stack startup | PASS. PostgreSQL/PostGIS became healthy; Flyway reported schema version 6 current; application started. |
| `/actuator/health` | HTTP 200. |
| Liveness | HTTP 200. |
| Readiness | HTTP 200; database health was UP. |
| `/api/v1/system-info` | HTTP 200. |
| `/actuator/prometheus` | HTTP 200. |
| Local Keycloak discovery | HTTP 200. |
| Synthetic client-credentials token | Issued by the local reference Keycloak. The protected event-delivery request returned HTTP 403 because that service principal had no applicable AyuVahak grant; authentication succeeded and authorization denied. |
| Cleanup | PASS. Containers and Compose network were stopped/removed; the local PostgreSQL volume was retained. |

The host `Invoke-WebRequest` attempt timed out through its configured Windows `HttpClient` proxy path; repeating the checks with `curl.exe` against `127.0.0.1` succeeded. This was a host client/proxy quirk, not an application failure.

## Negative scope and safety checks

- No Volume II business module, schema, API, or workflow was found in production sources.
- No FHIR, DHIS2, OpenHIE/OpenHIM, OpenLMIS, patient data model, frontend/BFF, AI/LLM, message broker, Kubernetes requirement, or cloud-specific runtime was introduced.
- Architecture tests run in normal verification; the deliberate architecture violation is a test-only fixture and is detected by the rule.
- All integration targets were disposable local containers. Compose Keycloak was bound to loopback; no public IdP or healthcare system was contacted.
- No real credentials or patient data were accessed. The local client/user values are synthetic development fixtures only.
- No remote Git operation or publication occurred.

## Acceptance result

**PASS** for Phase 2 implementation acceptance. This is based on the commands and tests above; it does not claim production compliance, a production recovery objective, or measured operational capacity.

## Freeze record

The local Phase 2 freeze commit is `9b3e2a2539cdd6b1a417c7e92ae619ad7da654aa`. It contains the accepted implementation, remediation, and evidence. This hash is recorded in a subsequent documentation-only commit to avoid self-reference. No remote is configured or used.
