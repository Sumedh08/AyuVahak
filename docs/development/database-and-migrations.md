# Database and migrations

Phase 1 uses PostgreSQL with the installed PostGIS extension, Spring JDBC, and Flyway Community. The database contains only migration metadata and PostGIS-owned objects at this stage; business schemas and tables are introduced by the phase that implements their owning context. See the [Phase 1 engineering decisions](../architecture/phase-1-engineering-decisions.md) for the ownership policy.

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

The initial `V1__enable_postgis.sql` migration enables the PostGIS extension already installed in the database image. It creates no AyuVahak business schema or business table. The database must have the PostGIS extension files installed before the application starts; an operator or provisioning step may preinstall the extension. If it is absent and the migration role cannot create it, startup fails visibly.

Only the owning platform or business context may add migrations for its objects. Do not edit an already-applied migration to change its meaning. Add a forward migration for corrections. For a failed or destructive change, use a verified backup and restore procedure appropriate to the deployment rather than relying on automatic rollback. No regulatory retention or production backup interval is implied here.

## Verification

`./mvnw verify` (or `mvnw.cmd verify` on Windows) runs `PostgresPostgisFoundationIT` against a disposable container pinned to `postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677`. It starts the real application context, checks Flyway history, calls `PostGIS_Full_Version()`, executes a Spring JDBC query, and verifies Spring-managed transaction rollback. The test uses synthetic platform data and does not contact an external database or healthcare service.
