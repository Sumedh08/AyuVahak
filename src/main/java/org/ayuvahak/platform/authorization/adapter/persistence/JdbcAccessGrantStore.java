package org.ayuvahak.platform.authorization.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.ayuvahak.platform.authorization.application.AccessGrantStore;
import org.ayuvahak.platform.authorization.domain.AccessGrant;
import org.ayuvahak.platform.authorization.domain.GrantScope;
import org.ayuvahak.platform.authorization.domain.GrantStatus;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.idempotency.configuration.CommandIdempotencyProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** JDBC adapter for Identity & Access-owned grants, bootstrap marker, and command receipts. */
@Repository
@Lazy
public class JdbcAccessGrantStore implements AccessGrantStore {

  private static final String GRANT_COLUMNS =
      "grant_id, principal_kind, principal_issuer, principal_identifier_type, "
          + "principal_identifier, authority_code, scope_kind, organization_issuer, "
          + "organization_identifier_type, organization_identifier, facility_issuer, "
          + "facility_identifier_type, facility_identifier, grant_status, created_at, revoked_at";
  private static final RowMapper<AccessGrant> GRANT_ROW_MAPPER =
      (result, rowNumber) -> {
        ActorKind kind = ActorKind.valueOf(result.getString("principal_kind"));
        String type = result.getString("principal_identifier_type");
        ActorReference principal =
            new ActorReference(
                kind,
                new ExternalReference(
                    new IssuerId(result.getString("principal_issuer")),
                    type,
                    result.getString("principal_identifier")));
        String status = result.getString("grant_status");
        Timestamp revoked = result.getTimestamp("revoked_at");
        return new AccessGrant(
            new InternalId(result.getObject("grant_id", UUID.class)),
            principal,
            result.getString("authority_code"),
            grantScope(result),
            GrantStatus.valueOf(status),
            result.getTimestamp("created_at").toInstant(),
            Optional.ofNullable(revoked).map(Timestamp::toInstant));
      };

  private final JdbcTemplate jdbc;
  private final CommandIdempotencyProperties idempotencyProperties;

  public JdbcAccessGrantStore(
      JdbcTemplate jdbc, CommandIdempotencyProperties idempotencyProperties) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.idempotencyProperties =
        Objects.requireNonNull(idempotencyProperties, "idempotencyProperties");
  }

  @Override
  public void lockForBootstrap() {
    jdbc.execute("LOCK TABLE identity_access.authorization_grant IN SHARE ROW EXCLUSIVE MODE");
  }

  @Override
  public boolean anyGrantOrBootstrapMarkerExists() {
    Boolean exists =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM identity_access.authorization_grant) "
                + "OR EXISTS (SELECT 1 FROM identity_access.bootstrap_marker)",
            Boolean.class);
    return Boolean.TRUE.equals(exists);
  }

  @Override
  public InternalId insertGrant(
      ActorReference principal, String authorityCode, GrantScope scope, Instant createdAt) {
    Objects.requireNonNull(principal, "principal");
    Objects.requireNonNull(scope, "scope");
    ExternalReference organization = scope.organization().orElse(null);
    ExternalReference facility = scope.facility().orElse(null);
    InternalId id = InternalId.generate();
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
            + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
            + "scope_kind, organization_issuer, organization_identifier_type, "
            + "organization_identifier, facility_issuer, facility_identifier_type, "
            + "facility_identifier, grant_status, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)",
        id.value(),
        principal.kind().name(),
        principal.identity().issuer().value(),
        principal.identity().resourceType(),
        principal.identity().value(),
        authorityCode,
        scope.kind().name(),
        organization == null ? null : organization.issuer().value(),
        organization == null ? null : organization.resourceType(),
        organization == null ? null : organization.value(),
        facility == null ? null : facility.issuer().value(),
        facility == null ? null : facility.resourceType(),
        facility == null ? null : facility.value(),
        Timestamp.from(createdAt));
    return id;
  }

  @Override
  public long advanceEventSequence(InternalId grantId) {
    Long next =
        jdbc.queryForObject(
            "UPDATE identity_access.authorization_grant SET aggregate_sequence = aggregate_sequence + 1 "
                + "WHERE grant_id = ? RETURNING aggregate_sequence",
            Long.class,
            grantId.value());
    if (next == null) {
      throw new IllegalStateException("grant event sequence was not advanced");
    }
    return next;
  }

  @Override
  public void completeBootstrap(
      InternalId grantId,
      String operatorIssuer,
      String operatorReference,
      String hostPrincipal,
      String databasePrincipal,
      Instant completedAt) {
    jdbc.update(
        "INSERT INTO identity_access.bootstrap_marker (marker_id, grant_id, operator_issuer, "
            + "operator_reference, host_principal, database_principal, completed_at) "
            + "VALUES (1, ?, ?, ?, ?, ?, ?)",
        grantId.value(),
        operatorIssuer,
        operatorReference,
        hostPrincipal,
        databasePrincipal,
        Timestamp.from(completedAt));
  }

  @Override
  public String currentDatabasePrincipal() {
    return jdbc.queryForObject("SELECT current_user", String.class);
  }

  @Override
  public ReceiptClaim claimReceipt(
      String scopeDigest, String keyDigest, String fingerprint, Instant now) {
    int inserted =
        jdbc.update(
            "INSERT INTO identity_access.grant_command_receipt (scope_digest, "
                + "idempotency_key_digest, request_fingerprint, receipt_state, recorded_at) "
                + "VALUES (?, ?, ?, 'IN_PROGRESS', ?) ON CONFLICT DO NOTHING",
            scopeDigest,
            keyDigest,
            fingerprint,
            Timestamp.from(now));
    if (inserted == 1) {
      return new ReceiptClaim(ReceiptState.OWNER, Optional.empty(), Optional.empty());
    }

    return jdbc.queryForObject(
        "SELECT request_fingerprint, receipt_state, response_status, result_reference "
            + "FROM identity_access.grant_command_receipt WHERE scope_digest = ? "
            + "AND idempotency_key_digest = ?",
        (result, rowNumber) -> {
          if (!fingerprint.equals(result.getString("request_fingerprint"))) {
            return new ReceiptClaim(ReceiptState.KEY_REUSED, Optional.empty(), Optional.empty());
          }
          String state = result.getString("receipt_state");
          if ("IN_PROGRESS".equals(state)) {
            return new ReceiptClaim(ReceiptState.IN_PROGRESS, Optional.empty(), Optional.empty());
          }
          return new ReceiptClaim(
              ReceiptState.REPLAY,
              Optional.of(result.getInt("response_status")),
              Optional.of(new InternalId(result.getObject("result_reference", UUID.class))));
        },
        scopeDigest,
        keyDigest);
  }

  @Override
  public void configureBoundedReceiptWait() {
    jdbc.execute(
        "SET LOCAL lock_timeout = '"
            + idempotencyProperties.getReceiptLockWait().toMillis()
            + "ms'");
  }

  @Override
  public void completeReceipt(
      String scopeDigest, String keyDigest, int status, InternalId resultReference, Instant now) {
    int changed =
        jdbc.update(
            "UPDATE identity_access.grant_command_receipt SET receipt_state = 'COMPLETED', "
                + "response_status = ?, result_reference = ?, recorded_at = ? "
                + "WHERE scope_digest = ? AND idempotency_key_digest = ? "
                + "AND receipt_state = 'IN_PROGRESS'",
            status,
            resultReference.value(),
            Timestamp.from(now),
            scopeDigest,
            keyDigest);
    if (changed != 1) {
      throw new IllegalStateException("grant command receipt could not be completed");
    }
  }

  @Override
  public List<AccessGrant> listGrants(Optional<UUID> afterId, int limit) {
    Objects.requireNonNull(afterId, "afterId");
    String sql =
        "SELECT "
            + GRANT_COLUMNS
            + " FROM identity_access.authorization_grant "
            + "WHERE TRUE "
            + (afterId.isPresent() ? "AND grant_id > ? " : "")
            + "ORDER BY grant_id LIMIT ?";
    if (afterId.isPresent()) {
      return jdbc.query(sql, GRANT_ROW_MAPPER, afterId.orElseThrow(), limit);
    }
    return jdbc.query(sql, GRANT_ROW_MAPPER, limit);
  }

  @Override
  public Optional<AccessGrant> findGrant(InternalId id) {
    return jdbc
        .query(
            "SELECT "
                + GRANT_COLUMNS
                + " FROM identity_access.authorization_grant WHERE grant_id = ?",
            GRANT_ROW_MAPPER,
            id.value())
        .stream()
        .findFirst();
  }

  @Override
  public OptionalLong revoke(InternalId id, Instant revokedAt) {
    List<Long> updated =
        jdbc.query(
            "UPDATE identity_access.authorization_grant SET grant_status = 'REVOKED', "
                + "revoked_at = ?, aggregate_sequence = aggregate_sequence + 1 "
                + "WHERE grant_id = ? AND grant_status = 'ACTIVE' RETURNING aggregate_sequence",
            (result, rowNumber) -> result.getLong(1),
            Timestamp.from(revokedAt),
            id.value());
    return updated.isEmpty() ? OptionalLong.empty() : OptionalLong.of(updated.getFirst());
  }

  @Override
  public boolean databaseContainsGrant(
      ActorReference principal, String authorityCode, GrantScope scope) {
    Objects.requireNonNull(scope, "scope");
    ExternalReference organization = scope.organization().orElse(null);
    ExternalReference facility = scope.facility().orElse(null);
    Boolean exists =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM identity_access.authorization_grant "
                + "WHERE principal_kind = ? AND principal_issuer = ? "
                + "AND principal_identifier_type = ? AND principal_identifier = ? "
                + "AND authority_code = ? AND scope_kind = ? "
                + "AND organization_issuer IS NOT DISTINCT FROM ? "
                + "AND organization_identifier_type IS NOT DISTINCT FROM ? "
                + "AND organization_identifier IS NOT DISTINCT FROM ? "
                + "AND facility_issuer IS NOT DISTINCT FROM ? "
                + "AND facility_identifier_type IS NOT DISTINCT FROM ? "
                + "AND facility_identifier IS NOT DISTINCT FROM ? AND grant_status = 'ACTIVE')",
            Boolean.class,
            principal.kind().name(),
            principal.identity().issuer().value(),
            principal.identity().resourceType(),
            principal.identity().value(),
            authorityCode,
            scope.kind().name(),
            organization == null ? null : organization.issuer().value(),
            organization == null ? null : organization.resourceType(),
            organization == null ? null : organization.value(),
            facility == null ? null : facility.issuer().value(),
            facility == null ? null : facility.resourceType(),
            facility == null ? null : facility.value());
    return Boolean.TRUE.equals(exists);
  }

  private static GrantScope grantScope(java.sql.ResultSet result) throws java.sql.SQLException {
    String kind = result.getString("scope_kind");
    ExternalReference organization =
        result.getString("organization_issuer") == null
            ? null
            : new ExternalReference(
                new IssuerId(result.getString("organization_issuer")),
                result.getString("organization_identifier_type"),
                result.getString("organization_identifier"));
    ExternalReference facility =
        result.getString("facility_issuer") == null
            ? null
            : new ExternalReference(
                new IssuerId(result.getString("facility_issuer")),
                result.getString("facility_identifier_type"),
                result.getString("facility_identifier"));
    return new GrantScope(
        org.ayuvahak.platform.executioncontext.ExecutionScope.Kind.valueOf(kind),
        Optional.ofNullable(organization),
        Optional.ofNullable(facility));
  }
}
