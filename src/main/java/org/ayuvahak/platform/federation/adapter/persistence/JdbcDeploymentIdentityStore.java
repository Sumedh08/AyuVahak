package org.ayuvahak.platform.federation.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.federation.application.DeploymentIdentityConflictException;
import org.ayuvahak.platform.federation.application.DeploymentIdentityConflictException.ConflictCode;
import org.ayuvahak.platform.federation.application.DeploymentIdentityStore;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.ayuvahak.platform.federation.domain.DeploymentIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC store for a single installation identity and sanitized identity conflict evidence. */
@Repository
public class JdbcDeploymentIdentityStore implements DeploymentIdentityStore {

  private final JdbcTemplate jdbc;

  public JdbcDeploymentIdentityStore(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public DeploymentIdentity registerOrVerify(
      DeploymentId deploymentId, String installationBindingDigest, Instant now) {
    Objects.requireNonNull(deploymentId, "deploymentId");
    Objects.requireNonNull(now, "now");
    if (installationBindingDigest == null || !installationBindingDigest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("installation binding digest is invalid");
    }

    int inserted =
        jdbc.update(
            "INSERT INTO platform_federation.deployment_identity "
                + "(singleton_id, deployment_id, installation_binding_digest, version, created_at, updated_at) "
                + "VALUES (1, ?, ?, 1, ?, ?) ON CONFLICT (singleton_id) DO NOTHING",
            deploymentId.value(),
            installationBindingDigest,
            Timestamp.from(now),
            Timestamp.from(now));
    DeploymentIdentityRecord registered = currentRecord();
    if (inserted == 1
        || (registered.deploymentId().equals(deploymentId.value())
            && registered.installationBindingDigest().equals(installationBindingDigest))) {
      return registered.toDomain();
    }

    ConflictCode code =
        registered.deploymentId().equals(deploymentId.value())
            ? ConflictCode.INSTALLATION_BINDING_MISMATCH
            : ConflictCode.DEPLOYMENT_ID_MISMATCH;
    jdbc.update(
        "INSERT INTO platform_federation.deployment_identity_conflict "
            + "(conflict_id, configured_deployment_id, registered_deployment_id, conflict_code, observed_at) "
            + "VALUES (?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        deploymentId.value(),
        registered.deploymentId(),
        code.name(),
        Timestamp.from(now));
    throw new DeploymentIdentityConflictException(code);
  }

  @Override
  public Optional<DeploymentIdentity> findCurrent() {
    return jdbc
        .query(
            "SELECT deployment_id, version, created_at, updated_at "
                + "FROM platform_federation.deployment_identity WHERE singleton_id = 1",
            (row, rowNumber) ->
                new DeploymentIdentity(
                    new DeploymentId(row.getString("deployment_id")),
                    row.getInt("version"),
                    row.getTimestamp("created_at").toInstant(),
                    row.getTimestamp("updated_at").toInstant()))
        .stream()
        .findFirst();
  }

  private DeploymentIdentityRecord currentRecord() {
    return jdbc.queryForObject(
        "SELECT deployment_id, installation_binding_digest, version, created_at, updated_at "
            + "FROM platform_federation.deployment_identity WHERE singleton_id = 1",
        (row, rowNumber) ->
            new DeploymentIdentityRecord(
                row.getString("deployment_id"),
                row.getString("installation_binding_digest"),
                row.getInt("version"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant()));
  }

  private record DeploymentIdentityRecord(
      String deploymentId,
      String installationBindingDigest,
      int version,
      Instant createdAt,
      Instant updatedAt) {

    DeploymentIdentity toDomain() {
      return new DeploymentIdentity(new DeploymentId(deploymentId), version, createdAt, updatedAt);
    }
  }
}
