# Production upgrade and migration recovery

This procedure defines the supported application-owned Flyway path for the
portable production profiles. It does not deploy an environment or promise an
automatic downgrade. Every production upgrade must name its source and target
application/runtime/database versions, migration inventory, lock and downtime
expectations, compatibility window, validation, and recovery choice in the
deployment change record.

## Before upgrade

1. Confirm the target release supports the deployed source release and database
   version. Use only the compatibility window recorded for those releases; do
   not assume arbitrary mixed-version operation.
2. Stop admission and drain or stop old application replicas unless the release
   explicitly documents an expand/contract mixed-version window.
3. Produce and verify a restorable PostgreSQL/PostGIS backup using the
   deployment's approved backup procedure. Preserve the restore evidence and
   its completion time in the deployment change record.
4. For the first target instance only, set
   `AYUVAHAK_MIGRATION_BACKUP_EVIDENCE_REFERENCE` to a bounded non-secret
   evidence identifier and `AYUVAHAK_MIGRATION_BACKUP_COMPLETED_AT` to its
   ISO-8601 UTC completion instant. These values attest that the operator
   completed the pre-upgrade backup gate; AyuVahak validates presence and time
   ordering, but does not inspect a backup repository or prove that the backup
   is restorable. Restore proof remains an operator/deployment acceptance
   responsibility.

## Apply and validate

Start one target application instance. The production Flyway strategy validates
the existing migration history before acting. When migrations are pending, it
requires the backup attestation and acquires a PostgreSQL advisory lock scoped
to the database. Only the instance holding that lock can run Flyway. A competing
instance fails startup without accepting traffic; after the migration owner is
ready, restart or start the remaining target replicas without the backup
attestation. When no migration is pending, that attestation is not required.

The Flyway initializer completes before the application context can finish
startup. A failed validation, missing backup attestation, competing migrator,
migration error, or unresolved migration history aborts startup. The instance
must not be put behind live ingress until it has reached readiness and the
operator has checked the declared post-upgrade invariants. Verify the expected
Flyway version, application logs for migration success, schema invariants, and
representative authorized read/write paths before restoring normal admission.

## Failure and recovery

If startup fails during migration, stop the rollout and keep every instance
that could use the partial schema out of service. Preserve the sanitized
migration error and database migration history for review. Do not run an
automatic down migration and do not run `flyway repair` as a generic retry.
First determine whether the failed migration left any partial effects and
whether the verified backup remains restorable. Recovery is one of:

- a reviewed forward-repair release with a new migration, tested against a
  disposable copy of the affected schema; or
- restoration from the verified pre-upgrade backup, followed by the documented
  application/configuration recovery procedure.

After repair or restore, validate migration history and owner invariants before
admitting traffic. Record the chosen path, resulting schema version, and
validation evidence. The application intentionally does not offer automatic
down migrations or silently mark a failed/unknown schema ready.

## Local verification

`ProductionFlywayMigrationStrategyIT` uses only a disposable local
PostgreSQL/PostGIS container and synthetic migration records. It verifies a
forward upgrade, backup-attestation gating, concurrent-startup serialization,
and that a failed migration prevents the Flyway startup initializer from
completing while releasing the advisory lock. It does not contact a production
database or assert that a deployment's backup is valid.
