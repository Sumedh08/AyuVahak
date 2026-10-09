# Database and migrations

The platform uses PostgreSQL with PostGIS, Spring JDBC, and Flyway Community. The current repository contains the Phase 2 platform schemas and the Phase 3 Directory schema. A business schema is introduced only when its owning context is implemented; no empty future schemas are pre-created. See the [Phase 1 engineering decisions](../architecture/phase-1-engineering-decisions.md) for the database ownership policy and the [Volume II architecture](../architecture/volume-2-core-care-coordination.md) for context boundaries.

## Runtime configuration

The application requires these environment variables:

| Variable | Purpose |
| --- | --- |
| `AYUVAHAK_DATABASE_URL` | JDBC URL for the AyuVahak database |
| `AYUVAHAK_DATABASE_USERNAME` | Database username |
| `AYUVAHAK_DATABASE_PASSWORD` | Database password |
| `AYUVAHAK_DATABASE_POOL_MAX_SIZE` | Optional connection-pool limit; defaults to `10` |

The URL, username, and password have no local or production fallback. Missing values prevent startup. Local-only values belong in the developer-controlled Compose environment and must never be reused as deployment credentials. Do not commit credentials.

Run the JVM with the UTC timezone. pgJDBC sends the JDK's default timezone during connection setup, so a JVM alias unknown to a PostgreSQL host can prevent connection startup. The Maven test forks and `spring-boot:run` use UTC; packaged runtime commands must use `-Duser.timezone=UTC` as well.

## Migration ownership and workflow

Versioned SQL migrations live in `src/main/resources/db/migration/` and use Flyway's `V<version>__<description>.sql` naming form. Flyway applies pending migrations during application startup and records the applied versions in `flyway_schema_history`. Application startup does not create or alter schema objects outside Flyway.

The initial `V1__enable_postgis.sql` migration enables the PostGIS extension already installed in the database image. Platform migrations then establish Phase 2-owned identity/access, audit, and eventing state. Phase 3 migrations `V8` through `V11` create Directory-owned Organization, Facility, Location, service, capability, assertion, identifier, and correction-history state. No other Volume II business schema is present. The database must have the PostGIS extension files installed before the application starts; an operator or provisioning step may preinstall the extension. If it is absent and the migration role cannot create it, startup fails visibly.

Only the owning platform or business context may add migrations for its objects. Do not edit an already-applied migration to change its meaning. Add a forward migration for corrections. For a failed or destructive change, use a verified backup and restore procedure appropriate to the deployment rather than relying on automatic rollback. No regulatory retention or production backup interval is implied here.

## Verification

`./mvnw verify` (or `mvnw.cmd verify` on Windows) runs persistence and Directory integration tests against disposable containers pinned to `postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677`. The platform foundation test checks Flyway history, calls `PostGIS_Full_Version()`, executes a Spring JDBC query, and verifies transaction rollback. Directory integration tests exercise the owning migrations, API, scope, audit, idempotency, search, and correction behavior against the real database. All test data is synthetic; tests do not contact external databases or healthcare services. Phase 3 acceptance passed on 2026-10-09; see the [Phase 3 acceptance record](../reviews/phase-3-acceptance.md).
