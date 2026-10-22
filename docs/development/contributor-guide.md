# Contributor guide

This guide covers the engineering platform, Phases 1–13 and current Phase 14 productization. Implemented interoperability is limited to the approved FHIR R4 boundary and named Directory mCSD and Referral IPS owner flows; Phase 9 supplies configured partner trust and secure transport. Transport coordinates transfers with manual operation and an optional configured provider adapter; it is not fleet-management software. Phase 11 adds bounded SSE hints and named federation operations through Phase 9. Phase 12 adds aggregate-only Analytics and a DHIS2 DataValueSet adapter through Phase 9. Read the [Phase 11](../reviews/phase-11-acceptance.md), [Phase 12](../reviews/phase-12-acceptance.md), [Volume IV](../reviews/volume-4-acceptance.md), and [Phase 13](../reviews/phase-13-acceptance.md) acceptance records. Phase 14 is authorized and in progress; Volume V is not complete. Development and acceptance use local synthetic systems only; no real partner is onboarded. Product scope and enduring architecture decisions live in the [architecture constitution](../architecture/constitution.md), [Phase 1 engineering decisions](../architecture/phase-1-engineering-decisions.md), [Phase 2 platform-kernel architecture](../architecture/phase-2-platform-kernel.md), [Volume II architecture](../architecture/volume-2-core-care-coordination.md), [Volume III architecture](../architecture/volume-3-interoperability-security-transport.md), [Volume IV architecture](../architecture/volume-4-resilient-operations-federation-public-health.md), and [Volume V design](../architecture/volume-5-production-hardening-optimization-intelligence.md). Phase-specific source and acceptance records are indexed from the [architecture documentation index](../architecture/README.md).

## Prerequisites

- A Java 25 JDK. Maven is not required; use the checked-in Maven Wrapper.
- Docker Engine or another Docker-compatible container runtime, with Docker Compose v2 support.
- Node.js 24.21.x when developing or verifying the UI; version is recorded in `ui/.nvmrc`.
- A terminal and Git client.

The supported Compose build installs the locked UI dependencies inside the pinned Node build image, so a local Node installation is needed only when editing or verifying frontend code. The local and test databases run in containers; do not connect tests to public/shared healthcare systems.

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

On Windows run the mode-selecting launcher:

```powershell
.\scripts\local\Start-AyuVahak.cmd
```

It asks for an explicit `SYNTHETIC_DEMO` or `EMPTY` first-run choice before services start. For automation, pass `-Mode SYNTHETIC_DEMO` or `-Mode EMPTY` to `Start-AyuVahak.ps1`. The launcher creates a local ignored `.env` with randomly generated database and Keycloak administrator credentials, records a local mode/install marker under ignored `.local-state`, validates Compose and creates an installation-specific Compose project. It never prints secret values. The mode is locked for that local installation; use another clean directory for the other mode.

Both modes import the public OIDC client configuration. Empty mode has no synthetic users; Demo mode imports the synthetic local realm. Business demo-data seeding is still in progress, so do not treat Demo mode as a populated workflow demonstration until that acceptance is complete. The user interface and application are packaged into the same application image. The app, database and Keycloak host ports are published to loopback only. No healthcare system, public IdP, cloud account or production credential is needed.

The local PostGIS image initializes its user as a bootstrap database administrator; the app uses it only inside the local Compose network so the initial migration can enable PostGIS. This is not a production role design. The Java 25, Node 24, PostGIS and Keycloak images are pinned by digest or exact tag in the Dockerfile and Compose manifests; first use retrieves those images and the locked npm/Maven dependencies. No value is baked as a credential into the application image.

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

The readiness response should report `UP` only after the PostgreSQL health contributor is ready. Liveness is process-local and does not require the database. The platform route reports non-sensitive service and build information. Directory routes are under `/api/v1/directory` and require a valid token plus an applicable Directory grant and execution scope. Prometheus metrics are served as scrape text. Other Actuator endpoints are not exposed. Keycloak discovery is available at `http://localhost:8081/realms/ayuvahak/.well-known/openid-configuration` by default. The application does not require Keycloak to start; token verification uses the configured issuer and JWK-set URI when a protected request arrives. Empty mode imports the public client without synthetic users; Demo mode imports the synthetic realm and disables the Resource Owner Password Credentials grant. See [HTTP API conventions](http-api.md) and [operational observability](observability.md) for response and privacy details.

To inspect the installation-specific project status and application logs:

```powershell
.\scripts\local\Get-AyuVahak.cmd
.\scripts\local\Get-AyuVahak.cmd -FollowLogs
```

Logs are structured JSON and contain safe request metadata and correlation identifiers. Request/response bodies, authorization headers, cookies, raw query strings, and sensitive values are not logged.

## Stop and reset

Stop and remove the containers while retaining local database state:

```powershell
.\scripts\local\Stop-AyuVahak.cmd
```

Only Synthetic Demo supports the explicit whole-install reset. This destroys that installation's entire local database and Keycloak volumes; it does not issue row-level deletion and cannot run in Empty mode:

```powershell
.\scripts\local\Stop-AyuVahak.ps1 -ResetDemo
```

Changing database initialization values in `.env` does not rewrite an initialized volume. Do not use the local volume as a backup. Use a separate clean directory to switch between Demo and Empty modes.

## Configuration and migrations

The local launcher sets the explicit `AYUVAHAK_DEPLOYMENT_MODE` and requires database settings plus `AYUVAHAK_OIDC_ISSUER`, `AYUVAHAK_OIDC_JWK_SET_URI`, `AYUVAHAK_OIDC_AUDIENCE`, and `AYUVAHAK_OIDC_SERVICE_CLIENT_IDS`; missing or invalid trust configuration prevents startup. The UI's OIDC authority and public client ID are build arguments, not secrets. `AYUVAHAK_OIDC_ALLOW_HTTP=true` is only for local Compose Keycloak; deployed configuration must use HTTPS and leave this exception disabled. `AYUVAHAK_OUTBOX_DELIVERED_EVENT_RETENTION` is a local synthetic 30-day replay value; each deployment chooses its own duration. `AYUVAHAK_IDEMPOTENCY_RECEIPT_LOCK_WAIT` defaults to five seconds and accepts only positive values up to 30 seconds. `AYUVAHAK_REFERRAL_TRANSFER_AMENDMENT_CHECK_CLIENT_ID` names the non-secret, separately scoped Referral service identity used only for Transfer's active-coordination status query; each deployment must grant it only the relevant origin scopes. The issuer is the exact token issuer (`http://localhost:8081/realms/ayuvahak` by default); the JWK-set URI uses the private Compose hostname (`http://keycloak:8080/realms/ayuvahak/protocol/openid-connect/certs`). `AYUVAHAK_HTTP_PORT` defaults to `8080`; `AYUVAHAK_KEYCLOAK_PORT` defaults to `8081`. The Compose network hostnames are `database` and `keycloak`; host-run configuration must use a developer-controlled database and identity provider, not a public service.

Migrations are versioned SQL files in `src/main/resources/db/migration/`. The initial migration enables the PostGIS extension already included in the image. Current migrations create Phase 2 platform state and the implemented Directory, Capacity, Referral, Matching, Transfer, Transport, Analytics, notification, federation, and secure-exchange state. Read [Database and migrations](database-and-migrations.md) before changing migrations. Do not edit an applied migration; add a forward migration. A future business schema is created only when its owning context is implemented.

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
| Compose reports missing mode or database variables | Run `scripts/local/Start-AyuVahak.cmd` and choose a mode; do not start the stack with an implicit mode. |
| Host port 8080 is already in use | Set `AYUVAHAK_HTTP_PORT` in ignored `.env` to another local port, recreate the app container, and use that port in checks. |
| App is not ready or exits during startup | Run `scripts/local/Get-AyuVahak.cmd` and inspect `scripts/local/Get-AyuVahak.cmd -FollowLogs`. Confirm the database health check succeeds and the app reports no migration/configuration failure. |
| Database container starts but app cannot connect | Check the Compose service hostname is `database`; do not use `localhost` from inside the app container. |
| PostGIS migration fails | Confirm the digest-pinned PostGIS image has completed initialization and check database logs. The image must include the extension files before Flyway enables it. |
| The installation marker and `.env` disagree | Stop; do not edit mode or credentials to point at another installation's volume. Preserve the local state and use the documented whole-demo reset only for Demo mode. |
| Integration tests cannot start containers | Start the Docker-compatible runtime and check `docker version`; then rerun `mvnw.cmd verify`. |
| PowerShell HTTP smoke check times out against localhost while the container is healthy | Check whether the shell's configured proxy is intercepting loopback traffic; retry with `curl.exe` against `127.0.0.1`. |
| Host-run JDBC connection fails with timezone error | Set `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC` in the shell starting Maven. |

## Scope and safety

This is a local reference installation, not a production deployment recipe. It uses generated local infrastructure credentials and a local identity provider. Phase 13 is complete and frozen; Phase 14 productization is active. The platform includes Phase 2 identity/access grants, audit, provenance, and event/recovery; Volume II Directory, Capacity, Referral, Matching, and Transfer; Volume III FHIR R4 adapters, secure exchange and bounded Transport; and Volume IV notifications, federation, aggregate Analytics and DHIS2 exchange. No real partner is onboarded. Analytics stores aggregate reporting facts only. The Synthetic Demo is an explicit local fixture loaded through owner APIs and includes separate least-privilege role personas; it is fictional and not for clinical use. The permanent restrictions on external writes, credentials, and healthcare test targets are in [AGENTS.md](../../AGENTS.md) and the [agent safety policy](../governance/agent-safety-policy.md).
