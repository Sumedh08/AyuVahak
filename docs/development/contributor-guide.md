# Contributor guide

This guide covers the engineering platform, Phase 2 identity/trust kernel, Volume II Phases 3–7, Volume III Phases 8–10, and Phase 11 realtime/federation operations. Implemented interoperability is limited to the approved FHIR R4 boundary and named Directory mCSD and Referral IPS owner flows; Phase 9 supplies configured partner trust and secure transport. Transport coordinates transfers with manual operation and an optional configured provider adapter; it is not fleet-management software. Phase 11 adds bounded SSE hints and named federation operations through Phase 9; the [Phase 11 acceptance record](../reviews/phase-11-acceptance.md) and [local SSE implementation notes](../research/phase-11-spring-sse-implementation-notes.md) describe the implementation and verification. Development and acceptance use local synthetic systems only. No real partner is onboarded. Clinical record storage and Phase 12 Analytics/DHIS2 remain outside the implemented scope. Product scope and enduring architecture decisions live in the [architecture constitution](../architecture/constitution.md), [Phase 1 engineering decisions](../architecture/phase-1-engineering-decisions.md), [Phase 2 platform-kernel architecture](../architecture/phase-2-platform-kernel.md), [Volume II architecture](../architecture/volume-2-core-care-coordination.md), [Volume III architecture](../architecture/volume-3-interoperability-security-transport.md), and [Volume IV architecture](../architecture/volume-4-resilient-operations-federation-public-health.md). Phase-specific source and acceptance records are indexed from the [architecture documentation index](../architecture/README.md); this guide links to them instead of restating them.

## Prerequisites

- A Java 25 JDK. Maven is not required; use the checked-in Maven Wrapper.
- Docker Engine or another Docker-compatible container runtime, with Docker Compose v2 support.
- A terminal and Git client.

Node.js is not needed for the current Java service. A later frontend phase may add it. The local and test databases run in containers; do not connect tests to public/shared healthcare systems.

Check the tools before starting:

```text
java -version
docker version
docker compose version
```

The wrapper retrieves its pinned Maven distribution and dependencies from Maven Central on first use. Container image pulls are also required the first time. No external healthcare service, cloud account, or production credential is needed.

## Build and test

Run the complete CI-equivalent verification from the repository root. On Windows:

```powershell
.\mvnw.cmd -B -ntp clean verify
```

On macOS/Linux:

```sh
./mvnw -B -ntp clean verify
```

This is the same substantive command used by the [least-privilege CI workflow](../../.github/workflows/ci.yml). It compiles, runs unit/HTTP/configuration/architecture tests, starts disposable PostgreSQL/PostGIS and Keycloak containers through Testcontainers, checks formatting, and packages the application. Docker must be running. The integration tests use only local containers and do not use an in-memory database as a PostgreSQL substitute or contact an external identity or healthcare service. Phase boundaries and later quality scenarios are described in the [quality attributes](../architecture/quality-attributes.md), [Phase 1 engineering decisions](../architecture/phase-1-engineering-decisions.md), [Phase 2 specification](../specs/phase-2-platform-kernel-spec.md), and [Phase 3 specification](../specs/phase-3-care-service-directory-spec.md).

To run only the architecture checks:

```powershell
.\mvnw.cmd -B -ntp -Dtest=Phase1ArchitectureTest test
```

On macOS/Linux, use `./mvnw -B -ntp -Dtest=Phase1ArchitectureTest test`.

The executable rules are in `src/test/java/org/ayuvahak/architecture/`. They inspect production classes and include a clearly identified test-only violating fixture.

## Start the local Compose stack

Compose runs the application, PostgreSQL/PostGIS, and the local Keycloak reference provider. The database and Keycloak are reachable by the application on the private Compose network; their host ports are bound to `127.0.0.1`. The ports needed for local development are published only to loopback.

Create the ignored local environment file once:

```powershell
Copy-Item .env.example .env
```

The checked-in values are synthetic local-only examples. Database and Keycloak administrator values are not secrets and must never be reused outside this disposable local environment. The local PostGIS image initializes its user as a bootstrap database administrator; the app uses it only inside the local Compose network so the initial migration can enable PostGIS. This convenience is not a production role design. Later deployment provisioning must use deployment-controlled credentials and least-privileged roles appropriate to its migrations. Services receive example values from `.env`; no value is baked into the application image. Do not place real credentials in `.env.example`, source files, Docker build arguments, or committed Compose configuration.

Validate and start:

```powershell
docker compose config --quiet
docker compose up --build --detach
docker compose ps
```

On macOS/Linux, copy the file with `cp .env.example .env`; the Compose commands are the same. The first startup may take time while the Java base images, PostGIS and Keycloak images, Maven distribution, and dependencies are downloaded. Keycloak starts in development mode with the synthetic realm in `infra/keycloak/realm-export.json`; this is a local reference provider, not a production deployment recipe. The app waits for the database health check before starting and does not require Keycloak at startup. It applies the versioned Flyway migration during startup. The build stage installs only the pinned `unzip` utility needed by the Maven Wrapper; the runtime image contains the JRE and application jar only.

The PostGIS image is pinned by manifest digest as `postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677`, matching the Testcontainers integration baseline. It resolved to PostgreSQL 18.6 and PostGIS 3.6.4. Keycloak is pinned as `quay.io/keycloak/keycloak:26.8.0@sha256:b0f60d489d51c5d113390bdf5461d4c06e6051be026c05549f2e1e10ec352bcc`. The Maven build image uses Eclipse Temurin Java 25.0.4.1+1 and the runtime image is its JRE counterpart; both base images are also pinned by manifest digest in the Dockerfile. Revisit those pins through normal dependency/image maintenance.

## Verify the running application

Wait for readiness to return `UP`, then check the platform route and management endpoints. On Windows PowerShell:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health/readiness
Invoke-RestMethod http://localhost:8080/actuator/health/liveness
Invoke-RestMethod http://localhost:8080/api/v1/system-info
Invoke-WebRequest http://localhost:8080/actuator/prometheus -Method Get
```

If `.env` selects a non-default `AYUVAHAK_HTTP_PORT`, substitute that port in each URL.

The readiness response should report `UP` only after the PostgreSQL health contributor is ready. Liveness is process-local and does not require the database. The platform route reports non-sensitive service and build information. Directory routes are under `/api/v1/directory` and require a valid token plus an applicable Directory grant and execution scope. Prometheus metrics are served as scrape text. Other Actuator endpoints are not exposed. Keycloak discovery is available at `http://localhost:8081/realms/ayuvahak/.well-known/openid-configuration` by default. The application does not require Keycloak to start; token verification uses the configured issuer and JWK-set URI when a protected request arrives. The realm contains synthetic identities, disables the Resource Owner Password Credentials grant, and uses client credentials only for the service-token integration seam. See [HTTP API conventions](http-api.md) and [operational observability](observability.md) for response and privacy details.

To inspect application startup and operational request logs:

```powershell
docker compose logs --follow app
```

Logs are structured JSON and contain safe request metadata and correlation identifiers. Request/response bodies, authorization headers, cookies, raw query strings, and sensitive values are not logged.

## Stop and reset

Stop and remove the containers while retaining the local database volume:

```powershell
docker compose down
```

Start again with `docker compose up --build --detach`. To destroy and recreate the disposable database state, including its migration history, run:

```powershell
docker compose down --volumes --remove-orphans
docker compose up --build --detach
```

The second command sequence permanently deletes this local Compose database volume. Changing database initialization values in `.env` does not rewrite an already initialized volume; remove the volume to initialize a fresh database with new local values. Do not use the volume as a backup.

## Configuration and migrations

The application requires the database settings and `AYUVAHAK_OIDC_ISSUER`, `AYUVAHAK_OIDC_JWK_SET_URI`, `AYUVAHAK_OIDC_AUDIENCE`, and `AYUVAHAK_OIDC_SERVICE_CLIENT_IDS`; missing or invalid trust configuration prevents startup. `AYUVAHAK_OIDC_ALLOW_HTTP` defaults to `false`; `.env.example` sets it to `true` only for the synthetic local Compose Keycloak endpoints. Deployed configuration must use HTTPS and leave this exception disabled. `AYUVAHAK_OUTBOX_DELIVERED_EVENT_RETENTION` is also required; the example provides a synthetic 30-day local value, while each deployment chooses a duration based on recovery and replay needs. Omission or a non-positive value fails startup. Expired delivered event records and their outbox-owned replay receipts may be purged; stalled and unhandled records are retained for explicit recovery. `AYUVAHAK_IDEMPOTENCY_RECEIPT_LOCK_WAIT` defaults to five seconds and accepts only positive values up to 30 seconds. `AYUVAHAK_REFERRAL_TRANSFER_AMENDMENT_CHECK_CLIENT_ID` names the non-secret, separately scoped Referral service identity used only for Transfer's active-coordination status query; it defaults to `ayuvahak-transfer-amendment-guard`. Each deployment must grant that service `TransferReferralAmendmentCheck` only at the relevant origin organization/facility scopes. Compose supplies these values from `.env`. The issuer is the exact token issuer (`http://localhost:8081/realms/ayuvahak` by default); the JWK-set URI uses the private Compose hostname (`http://keycloak:8080/realms/ayuvahak/protocol/openid-connect/certs`). `AYUVAHAK_HTTP_PORT` defaults to `8080`; `AYUVAHAK_KEYCLOAK_PORT` defaults to `8081`. The Compose network hostnames are `database` and `keycloak`; host-run configuration must use a developer-controlled database and identity provider, not a public service.

Migrations are versioned SQL files in `src/main/resources/db/migration/`. The initial migration enables the PostGIS extension already included in the image. Current migrations create Phase 2 platform state and the implemented Directory, Capacity, Referral, Matching, and Transfer schemas. Read [Database and migrations](database-and-migrations.md) before changing migrations. Do not edit an applied migration; add a forward migration. A future business schema is created only when its owning context is implemented.

The documented run path is the Compose app container. If a future local code-debugging workflow runs the app on the host, it must use a developer-controlled PostgreSQL/PostGIS instance with explicit local connection settings and `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`; the default Compose configuration intentionally does not publish its database port. Never point a host-run app at a public/shared database.

## Observability endpoints

The Compose stack publishes these endpoints on the local app port:

| Endpoint | Meaning |
| --- | --- |
| `/actuator/health` | Aggregate health with component details hidden. |
| `/actuator/health/liveness` | Whether this process is alive. |
| `/actuator/health/readiness` | Whether database-dependent platform work can be served. |
| `/actuator/prometheus` | Pull-based platform, JVM, HTTP, and database-pool metrics. |

No telemetry backend or collector is required. Optional OTLP tracing export is disabled unless explicitly configured. Do not configure it to send telemetry to an unapproved endpoint. Management paths retain the existing public local Phase 1 behavior; later deployment network controls must protect them. Full logging, metrics, tracing, and safe-field guidance is in [Operational observability](observability.md).

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Compose reports missing database variables | Copy `.env.example` to `.env` and run Compose from the repository root. Keep the values synthetic and local. |
| Host port 8080 is already in use | Set `AYUVAHAK_HTTP_PORT` in ignored `.env` to another local port, recreate the app container, and use that port in checks. |
| App is not ready or exits during startup | Run `docker compose ps` and `docker compose logs database app`. Confirm the database health check succeeds and the app reports no migration/configuration failure. |
| Database container starts but app cannot connect | Check the Compose service hostname is `database`; do not use `localhost` from inside the app container. |
| PostGIS migration fails | Confirm the digest-pinned PostGIS image has completed initialization and check database logs. The image must include the extension files before Flyway enables it. |
| A changed `.env` value has no effect on an existing database | Initialization variables apply only when the data directory is empty. Back up any needed local data, then explicitly remove the disposable volume with `docker compose down --volumes`. |
| Integration tests cannot start containers | Start the Docker-compatible runtime and check `docker version`; then rerun `mvnw.cmd verify`. |
| PowerShell HTTP smoke check times out against localhost while the container is healthy | Check whether the shell's configured proxy is intercepting loopback traffic; retry with `curl.exe` against `127.0.0.1`. |
| Host-run JDBC connection fails with timezone error | Set `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC` in the shell starting Maven. |

## Scope and safety

This is a local developer environment, not a production deployment recipe. It has synthetic credentials and a local authentication reference provider. The current platform includes Phase 2 identity/access grants, audit, provenance, and event/recovery capabilities; Volume II Directory, Capacity, Referral, Matching, and Transfer; Volume III's FHIR R4 adapters, configured secure exchange, and bounded Transport coordination; and Phase 11 notifications and federation. No real partner is onboarded, and all integration tests use local mocks, fixtures, or disposable containers. Clinical record storage and Phase 12 Analytics/DHIS2 remain unimplemented. This environment contains no patient data. The permanent restrictions on external writes, credentials, and healthcare test targets are in [AGENTS.md](../../AGENTS.md) and the [agent safety policy](../governance/agent-safety-policy.md). Follow them for all later phases.
