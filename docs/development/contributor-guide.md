# Contributor guide

This guide covers the Phase 1 engineering platform. Product scope and enduring architecture decisions live in the [architecture constitution](../architecture/constitution.md), [Phase 1 engineering decisions](../architecture/phase-1-engineering-decisions.md), and [ADR register](../adr/README.md); this guide links to them instead of restating them.

## Prerequisites

- A Java 25 JDK. Maven is not required; use the checked-in Maven Wrapper.
- Docker Engine or another Docker-compatible container runtime, with Docker Compose v2 support.
- A terminal and Git client.

Node.js is not needed for this phase. The local and test databases run in containers; do not connect tests to public/shared healthcare systems.

Check the tools before starting:

```text
java -version
docker version
docker compose version
```

The wrapper retrieves its pinned Maven distribution and dependencies from Maven Central on first use. Container image pulls are also required the first time. No external healthcare service, cloud account, or production credential is needed.

## Build and test

Run commands from the repository root. On Windows use `mvnw.cmd`; on macOS/Linux use `./mvnw`.

```powershell
.\mvnw.cmd -B -ntp test
.\mvnw.cmd -B -ntp verify
```

On macOS/Linux:

```sh
./mvnw -B -ntp test
./mvnw -B -ntp verify
```

`test` runs the unit, HTTP, configuration, and architecture checks. `verify` also runs the PostgreSQL/PostGIS integration tests through Testcontainers and the formatting check, so Docker must be running. The integration tests start disposable local containers and do not use an in-memory database as a PostgreSQL substitute. The `verify` lifecycle packages the application. The full test categories and Phase 1 limits are described in the [quality attributes](../architecture/quality-attributes.md) and [Phase 1 engineering decisions](../architecture/phase-1-engineering-decisions.md).

To run only the architecture checks:

```powershell
.\mvnw.cmd -B -ntp -Dtest=Phase1ArchitectureTest test
```

On macOS/Linux, use `./mvnw -B -ntp -Dtest=Phase1ArchitectureTest test`.

The executable rules are in `src/test/java/org/ayuvahak/architecture/`. They inspect production classes and include a clearly identified test-only violating fixture.

## Start the local Compose stack

Compose runs the application and PostgreSQL/PostGIS. The database is reachable by the application on the private Compose network and its port is not published to the host. Only the application port is published, bound to `127.0.0.1`.

Create the ignored local environment file once:

```powershell
Copy-Item .env.example .env
```

The checked-in values are synthetic local-only examples. In particular, the database password is not a secret and must never be reused outside this disposable local environment. The local PostGIS image initializes that user as its bootstrap database administrator; the app uses it only inside the local Compose network so the initial migration can enable PostGIS. This convenience is not a production role design. Later deployment provisioning must use deployment-controlled credentials and least-privileged roles appropriate to its migrations. The application and database both receive the example value from `.env`; no value is baked into the image. Do not place real credentials in `.env.example`, source files, Docker build arguments, or committed Compose configuration.

Validate and start:

```powershell
docker compose config --quiet
docker compose up --build --detach
docker compose ps
```

On macOS/Linux, copy the file with `cp .env.example .env`; the Compose commands are the same. The first startup may take time while the Java base images, PostGIS image, Maven distribution, and dependencies are downloaded. The app waits for the database health check before starting. It applies the versioned Flyway migration during startup. The build stage installs only the pinned `unzip` utility needed by the Maven Wrapper; the runtime image contains the JRE and application jar only.

The current PostGIS image tag is `postgis/postgis:18-3.6`, matching the Testcontainers integration baseline. The image retrieved for this implementation resolved to PostgreSQL 18.6 and PostGIS 3.6.4. The Maven build image uses Eclipse Temurin Java 25.0.4.1+1 and the runtime image is its JRE counterpart; both base images are pinned by manifest digest in the Dockerfile. Revisit those pins through normal dependency/image maintenance.

## Verify the running application

Wait for readiness to return `UP`, then check the platform route and management endpoints. On Windows PowerShell:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health/readiness
Invoke-RestMethod http://localhost:8080/actuator/health/liveness
Invoke-RestMethod http://localhost:8080/api/v1/system-info
Invoke-WebRequest http://localhost:8080/actuator/prometheus -Method Get
```

If `.env` selects a non-default `AYUVAHAK_HTTP_PORT`, substitute that port in each URL.

The readiness response should report `UP` only after the PostgreSQL health contributor is ready. Liveness is process-local and does not require the database. The application route reports non-sensitive service and build information. Prometheus metrics are served as scrape text. Other Actuator endpoints are not exposed. See [HTTP API conventions](http-api.md) and [operational observability](observability.md) for response and privacy details.

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

The application requires `AYUVAHAK_DATABASE_URL`, `AYUVAHAK_DATABASE_USERNAME`, and `AYUVAHAK_DATABASE_PASSWORD`; missing values prevent startup. Compose supplies these values to the container from the local `.env` file. `AYUVAHAK_HTTP_PORT` selects the loopback-bound host port and defaults to `8080`. The Compose network hostname is `database`; host-run configuration uses `localhost` as the database host only if the Compose file is deliberately changed to publish the database port locally.

Migrations are versioned SQL files in `src/main/resources/db/migration/`. The initial migration enables the PostGIS extension already included in the image and creates no AyuVahak business schema or table. Read [Database and migrations](database-and-migrations.md) before changing migrations. Do not edit an applied migration; add a forward migration. Business schemas are created only when their owning context is implemented.

The documented run path is the Compose app container. If a future local code-debugging workflow runs the app on the host, it must use a developer-controlled PostgreSQL/PostGIS instance with explicit local connection settings and `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`; the default Compose configuration intentionally does not publish its database port. Never point a host-run app at a public/shared database.

## Observability endpoints

The Compose stack publishes these endpoints on the local app port:

| Endpoint | Meaning |
| --- | --- |
| `/actuator/health` | Aggregate health with component details hidden. |
| `/actuator/health/liveness` | Whether this process is alive. |
| `/actuator/health/readiness` | Whether database-dependent platform work can be served. |
| `/actuator/prometheus` | Pull-based platform, JVM, HTTP, and database-pool metrics. |

No telemetry backend or collector is required. Optional OTLP tracing export is disabled unless explicitly configured. Do not configure it to send telemetry to an unapproved endpoint. Keep management access on a trusted operator network in any later deployment; Phase 1 does not implement authentication for management paths. Full logging, metrics, tracing, and safe-field guidance is in [Operational observability](observability.md).

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Compose reports missing database variables | Copy `.env.example` to `.env` and run Compose from the repository root. Keep the values synthetic and local. |
| Host port 8080 is already in use | Set `AYUVAHAK_HTTP_PORT` in ignored `.env` to another local port, recreate the app container, and use that port in checks. |
| App is not ready or exits during startup | Run `docker compose ps` and `docker compose logs database app`. Confirm the database health check succeeds and the app reports no migration/configuration failure. |
| Database container starts but app cannot connect | Check the Compose service hostname is `database`; do not use `localhost` from inside the app container. |
| PostGIS migration fails | Confirm the `postgis/postgis:18-3.6` image has completed initialization and check database logs. The image must include the extension files before Flyway enables it. |
| A changed `.env` value has no effect on an existing database | Initialization variables apply only when the data directory is empty. Back up any needed local data, then explicitly remove the disposable volume with `docker compose down --volumes`. |
| Integration tests cannot start containers | Start the Docker-compatible runtime and check `docker version`; then rerun `mvnw.cmd verify`. |
| Host-run JDBC connection fails with timezone error | Set `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC` in the shell starting Maven. |

## Scope and safety

This is a local developer environment, not a production deployment recipe. It has synthetic credentials and no authentication or authorization. It contains no healthcare workflow or patient data. The permanent restrictions on external writes, credentials, and healthcare test targets are in [AGENTS.md](../../AGENTS.md) and the [agent safety policy](../governance/agent-safety-policy.md). Follow them for all later phases.
