package org.ayuvahak.platform.configuration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.concurrent.Executor;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;

/** Runs production migrations only after backup attestation and under one database-scoped lease. */
final class ProductionFlywayMigrationStrategy implements FlywayMigrationStrategy {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(ProductionFlywayMigrationStrategy.class);

  // Reserved for AyuVahak's production Flyway startup path; the pair scopes the lock to one DB.
  private static final int LOCK_NAMESPACE = 1_096_379_734;
  private static final int LOCK_RESOURCE = 1_296_648_018;

  private final ProductionMigrationProperties properties;

  ProductionFlywayMigrationStrategy(ProductionMigrationProperties properties) {
    this.properties = properties;
  }

  @Override
  public void migrate(Flyway flyway) {
    validateHistory(flyway);
    if (flyway.info().pending().length == 0) {
      return;
    }

    requirePreUpgradeBackupEvidence();
    migrateAsSingleController(flyway);
  }

  private void migrateAsSingleController(Flyway flyway) {
    try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
      if (!tryAcquire(connection)) {
        throw new IllegalStateException(
            "another production migrator is active; application startup is blocked");
      }

      RuntimeException migrationFailure = null;
      try {
        validateHistory(flyway);
        if (flyway.info().pending().length > 0) {
          requirePreUpgradeBackupEvidence();
          flyway.migrate();
        }
        validateHistory(flyway);
        if (flyway.info().pending().length > 0) {
          throw new IllegalStateException(
              "production database still has pending migrations; application startup is blocked");
        }
      } catch (RuntimeException exception) {
        migrationFailure = exception;
        throw exception;
      } finally {
        try {
          release(connection);
        } catch (SQLException exception) {
          logLockFailure(exception);
          invalidate(connection);
          IllegalStateException releaseFailure =
              new IllegalStateException(
                  "production migration lock could not be safely released; application startup is blocked");
          if (migrationFailure != null) {
            migrationFailure.addSuppressed(releaseFailure);
          } else {
            throw releaseFailure;
          }
        }
      }
    } catch (SQLException exception) {
      logLockFailure(exception);
      throw new IllegalStateException(
          "production migration coordination failed; application startup is blocked");
    }
  }

  private void validateHistory(Flyway flyway) {
    MigrationInfo[] migrations = flyway.info().all();
    boolean hasAppliedHistory =
        Arrays.stream(migrations).anyMatch(migration -> migration.getState().isApplied());
    if (hasAppliedHistory) {
      new FluentConfiguration()
          .configuration(flyway.getConfiguration())
          .ignoreMigrationPatterns("*:pending")
          .load()
          .validate();
    }
    for (MigrationInfo migration : migrations) {
      MigrationState state = migration.getState();
      if (state.isFailed()
          || state == MigrationState.MISSING_SUCCESS
          || state == MigrationState.FUTURE_SUCCESS
          || state == MigrationState.FUTURE_FAILED) {
        throw new IllegalStateException(
            "production database migration history is unsafe; application startup is blocked");
      }
    }
  }

  private void requirePreUpgradeBackupEvidence() {
    String reference = properties.getBackupEvidenceReference();
    String completedAt = properties.getBackupCompletedAt();
    if (reference == null
        || reference.isBlank()
        || reference.length() > 256
        || reference.chars().anyMatch(Character::isISOControl)
        || completedAt == null
        || completedAt.isBlank()) {
      throw new IllegalStateException(
          "pre-upgrade backup evidence is required before production migration; application startup is blocked");
    }

    try {
      if (Instant.parse(completedAt).isAfter(Instant.now())) {
        throw new IllegalStateException(
            "pre-upgrade backup evidence must predate production migration; application startup is blocked");
      }
    } catch (DateTimeParseException exception) {
      throw new IllegalStateException(
          "pre-upgrade backup completion time must be an ISO-8601 instant; application startup is blocked");
    }
  }

  private static boolean tryAcquire(Connection connection) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")) {
      statement.setInt(1, LOCK_NAMESPACE);
      statement.setInt(2, LOCK_RESOURCE);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() && result.getBoolean(1);
      }
    }
  }

  private static void release(Connection connection) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_advisory_unlock(?, ?)")) {
      statement.setInt(1, LOCK_NAMESPACE);
      statement.setInt(2, LOCK_RESOURCE);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || !result.getBoolean(1)) {
          throw new SQLException("migration advisory lock was not held");
        }
      }
    }
  }

  private static void logLockFailure(SQLException exception) {
    LOGGER.error("Production migration coordination failed; SQL state {}", exception.getSQLState());
  }

  private static void invalidate(Connection connection) {
    Executor directExecutor = Runnable::run;
    try {
      connection.abort(directExecutor);
    } catch (SQLException | RuntimeException exception) {
      LOGGER.error("Could not invalidate connection after migration-lock release failure");
    }
  }
}
