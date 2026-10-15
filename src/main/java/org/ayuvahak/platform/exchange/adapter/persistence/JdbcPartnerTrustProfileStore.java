package org.ayuvahak.platform.exchange.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.application.PartnerProfileConflictException;
import org.ayuvahak.platform.exchange.application.PartnerProfileNotFoundException;
import org.ayuvahak.platform.exchange.application.PartnerProfileStateConflictException;
import org.ayuvahak.platform.exchange.application.PartnerTrustProfileStore;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerProfileState;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.platform.idempotency.configuration.CommandIdempotencyProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC adapter for platform-owned partner trust profiles and administration receipts. */
@Repository
@Lazy
public class JdbcPartnerTrustProfileStore implements PartnerTrustProfileStore {

  private static final String VERSION_COLUMNS =
      "v.profile_version_id, v.partner_id, p.partner_key, v.version_number, v.state, "
          + "v.organization_issuer, v.organization_identifier, v.endpoint_origin, v.base_path, "
          + "v.authentication_method, v.credential_reference, v.token_signing_keys_reference, "
          + "v.token_origin, v.token_path, "
          + "v.oauth_issuer, v.oauth_audience, v.oauth_client_id, v.tls_policy, "
          + "v.inbound_principal_issuer, v.inbound_principal_client_id, "
          + "v.tls_trust_reference, v.connect_timeout_millis, v.response_timeout_millis, "
          + "v.operation_timeout_millis, v.max_response_bytes, v.max_decompressed_bytes, "
          + "v.max_pages, v.max_resources, v.max_nesting_depth, v.max_attempts, v.retry_class, "
          + "v.created_at, v.updated_at";

  private final JdbcTemplate jdbc;
  private final CommandIdempotencyProperties idempotencyProperties;

  public JdbcPartnerTrustProfileStore(
      JdbcTemplate jdbc, CommandIdempotencyProperties idempotencyProperties) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.idempotencyProperties =
        Objects.requireNonNull(idempotencyProperties, "idempotencyProperties");
  }

  @Override
  public void configureBoundedReceiptWait() {
    jdbc.execute(
        "SET LOCAL lock_timeout = '"
            + idempotencyProperties.getReceiptLockWait().toMillis()
            + "ms'");
  }

  @Override
  public PartnerProfileVersion createInitial(PartnerTrustConfiguration configuration, Instant now) {
    InternalId partnerId = InternalId.generate();
    jdbc.update(
        "INSERT INTO platform_exchange.partner_profile (partner_id, partner_key, current_version, created_at) "
            + "VALUES (?, ?, 0, ?)",
        partnerId.value(),
        configuration.partnerKey().value(),
        Timestamp.from(now));
    return insertVersion(partnerId.value(), configuration, 1, now);
  }

  @Override
  public PartnerProfileVersion createNextVersion(
      PartnerTrustConfiguration configuration, int expectedCurrentVersion, Instant now) {
    PartnerIdentity identity = lockPartner(configuration.partnerKey());
    if (identity.currentVersion() != expectedCurrentVersion || expectedCurrentVersion < 1) {
      throw new PartnerProfileConflictException();
    }
    Optional<PartnerProfileVersion> current =
        findVersion(configuration.partnerKey(), identity.currentVersion());
    if (current.isEmpty()) {
      throw new IllegalStateException("partner profile current version is missing");
    }
    if (current.orElseThrow().state() == PartnerProfileState.DRAFT) {
      jdbc.update(
          "UPDATE platform_exchange.partner_profile_version SET state = 'SUPERSEDED', updated_at = ? "
              + "WHERE partner_id = ? AND version_number = ? AND state = 'DRAFT'",
          Timestamp.from(now),
          identity.id(),
          identity.currentVersion());
    }
    int nextVersion = identity.currentVersion() + 1;
    jdbc.update(
        "UPDATE platform_exchange.partner_profile SET current_version = ? WHERE partner_id = ?",
        nextVersion,
        identity.id());
    return insertVersion(identity.id(), configuration, nextVersion, now);
  }

  @Override
  public PartnerProfileVersion activate(PartnerKey partnerKey, int version, Instant now) {
    PartnerIdentity identity = lockPartner(partnerKey);
    requireCurrentVersion(identity, version);
    PartnerProfileVersion draft = requireVersion(partnerKey, version);
    if (draft.state() != PartnerProfileState.DRAFT) {
      throw new PartnerProfileStateConflictException();
    }
    jdbc.update(
        "UPDATE platform_exchange.partner_profile_version SET state = 'SUPERSEDED', updated_at = ? "
            + "WHERE partner_id = ? AND state = 'ACTIVE'",
        Timestamp.from(now),
        identity.id());
    jdbc.update(
        "UPDATE platform_exchange.partner_profile_version SET state = 'ACTIVE', updated_at = ? "
            + "WHERE partner_id = ? AND version_number = ? AND state = 'DRAFT'",
        Timestamp.from(now),
        identity.id(),
        version);
    return requireVersion(partnerKey, version);
  }

  @Override
  public PartnerProfileVersion suspend(PartnerKey partnerKey, Instant now) {
    PartnerIdentity identity = lockPartner(partnerKey);
    PartnerProfileVersion active =
        findLifecycleVersion(partnerKey, "ACTIVE")
            .orElseThrow(PartnerProfileStateConflictException::new);
    jdbc.update(
        "UPDATE platform_exchange.partner_profile_version SET state = 'SUSPENDED', updated_at = ? "
            + "WHERE profile_version_id = ? AND partner_id = ? AND state = 'ACTIVE'",
        Timestamp.from(now),
        active.id().value(),
        identity.id());
    return requireVersion(partnerKey, active.version());
  }

  @Override
  public PartnerProfileVersion revoke(PartnerKey partnerKey, Instant now) {
    PartnerIdentity identity = lockPartner(partnerKey);
    PartnerProfileVersion revocable =
        findLifecycleVersion(partnerKey, "ACTIVE", "SUSPENDED")
            .orElseThrow(PartnerProfileStateConflictException::new);
    jdbc.update(
        "UPDATE platform_exchange.partner_profile_version SET state = 'REVOKED', updated_at = ? "
            + "WHERE profile_version_id = ? AND partner_id = ? AND state IN ('ACTIVE', 'SUSPENDED')",
        Timestamp.from(now),
        revocable.id().value(),
        identity.id());
    // A draft reviewed before revocation must not be a shortcut back to ACTIVE. A new version
    // must be prepared and reviewed after revocation.
    jdbc.update(
        "UPDATE platform_exchange.partner_profile_version SET state = 'SUPERSEDED', updated_at = ? "
            + "WHERE partner_id = ? AND state = 'DRAFT'",
        Timestamp.from(now),
        identity.id());
    return requireVersion(partnerKey, revocable.version());
  }

  @Override
  public List<PartnerProfileVersion> versions(PartnerKey partnerKey) {
    return jdbc.query(
        "SELECT "
            + VERSION_COLUMNS
            + " FROM platform_exchange.partner_profile_version v "
            + "JOIN platform_exchange.partner_profile p ON p.partner_id = v.partner_id "
            + "WHERE p.partner_key = ? ORDER BY v.version_number DESC LIMIT 100",
        this::mapVersion,
        partnerKey.value());
  }

  @Override
  public Optional<PartnerProfileVersion> findById(InternalId versionId) {
    return jdbc
        .query(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM platform_exchange.partner_profile_version v "
                + "JOIN platform_exchange.partner_profile p ON p.partner_id = v.partner_id "
                + "WHERE v.profile_version_id = ?",
            this::mapVersion,
            versionId.value())
        .stream()
        .findFirst();
  }

  @Override
  public Optional<PartnerProfileVersion> findActive(
      PartnerKey partnerKey, PartnerTrustConfiguration.AllowedOperation operation) {
    Objects.requireNonNull(operation, "operation");
    return jdbc
        .query(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM platform_exchange.partner_profile_version v "
                + "JOIN platform_exchange.partner_profile p ON p.partner_id = v.partner_id "
                + "JOIN platform_exchange.partner_profile_operation o "
                + "ON o.profile_version_id = v.profile_version_id "
                + "WHERE p.partner_key = ? AND v.state = 'ACTIVE' AND o.owner_context = ? "
                + "AND o.operation_code = ? AND o.direction = ? AND o.protocol = ? AND o.profile = ?",
            this::mapVersion,
            partnerKey.value(),
            operation.ownerContext().name(),
            operation.operationCode(),
            operation.direction().name(),
            operation.protocol().name(),
            operation.profile())
        .stream()
        .findFirst();
  }

  @Override
  public Optional<PartnerProfileVersion> findActiveInbound(
      ActorReference principal, PartnerTrustConfiguration.AllowedOperation operation) {
    Objects.requireNonNull(principal, "principal");
    Objects.requireNonNull(operation, "operation");
    if (principal.kind() != ActorKind.SERVICE
        || operation.direction() != PartnerTrustConfiguration.Direction.INBOUND) {
      return Optional.empty();
    }
    return jdbc
        .query(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM platform_exchange.partner_profile_version v "
                + "JOIN platform_exchange.partner_profile p ON p.partner_id = v.partner_id "
                + "JOIN platform_exchange.partner_profile_operation o "
                + "ON o.profile_version_id = v.profile_version_id "
                + "WHERE v.state = 'ACTIVE' AND v.inbound_principal_issuer = ? "
                + "AND v.inbound_principal_client_id = ? AND o.owner_context = ? "
                + "AND o.operation_code = ? AND o.direction = ? AND o.protocol = ? AND o.profile = ?",
            this::mapVersion,
            principal.identity().issuer().value(),
            principal.identity().value(),
            operation.ownerContext().name(),
            operation.operationCode(),
            operation.direction().name(),
            operation.protocol().name(),
            operation.profile())
        .stream()
        .findFirst();
  }

  @Override
  public ReceiptClaim claimReceipt(
      CommandReceiptKey key, RequestFingerprint fingerprint, Instant now) {
    int inserted =
        jdbc.update(
            "INSERT INTO platform_exchange.partner_admin_command_receipt "
                + "(scope_digest, idempotency_key_digest, request_fingerprint, receipt_state, recorded_at) "
                + "VALUES (?, ?, ?, 'IN_PROGRESS', ?) ON CONFLICT DO NOTHING",
            key.scopeDigest(),
            key.idempotencyKey().value(),
            fingerprint.value(),
            Timestamp.from(now));
    if (inserted == 1) {
      return new ReceiptClaim(ReceiptState.OWNER, Optional.empty(), Optional.empty());
    }
    return jdbc.queryForObject(
        "SELECT request_fingerprint, receipt_state, response_status, result_version_id "
            + "FROM platform_exchange.partner_admin_command_receipt "
            + "WHERE scope_digest = ? AND idempotency_key_digest = ?",
        (result, rowNumber) -> {
          if (!fingerprint.value().equals(result.getString("request_fingerprint"))) {
            return new ReceiptClaim(ReceiptState.KEY_REUSED, Optional.empty(), Optional.empty());
          }
          if ("IN_PROGRESS".equals(result.getString("receipt_state"))) {
            return new ReceiptClaim(ReceiptState.IN_PROGRESS, Optional.empty(), Optional.empty());
          }
          return new ReceiptClaim(
              ReceiptState.REPLAY,
              Optional.of(result.getInt("response_status")),
              Optional.of(new InternalId(result.getObject("result_version_id", UUID.class))));
        },
        key.scopeDigest(),
        key.idempotencyKey().value());
  }

  @Override
  public void completeReceipt(
      CommandReceiptKey key, int responseStatus, InternalId resultVersion, Instant now) {
    int changed =
        jdbc.update(
            "UPDATE platform_exchange.partner_admin_command_receipt "
                + "SET receipt_state = 'COMPLETED', response_status = ?, result_version_id = ?, recorded_at = ? "
                + "WHERE scope_digest = ? AND idempotency_key_digest = ? AND receipt_state = 'IN_PROGRESS'",
            responseStatus,
            resultVersion.value(),
            Timestamp.from(now),
            key.scopeDigest(),
            key.idempotencyKey().value());
    if (changed != 1) {
      throw new IllegalStateException("partner administration receipt could not be completed");
    }
  }

  private PartnerProfileVersion insertVersion(
      UUID partnerId, PartnerTrustConfiguration configuration, int version, Instant now) {
    InternalId versionId = InternalId.generate();
    PartnerTrustConfiguration.Authentication authentication = configuration.authentication();
    PartnerTrustConfiguration.Limits limits = configuration.limits();
    jdbc.update(
        "INSERT INTO platform_exchange.partner_profile_version (profile_version_id, partner_id, "
            + "version_number, state, organization_issuer, organization_identifier, endpoint_origin, "
            + "base_path, authentication_method, credential_reference, token_signing_keys_reference, "
            + "token_origin, token_path, "
            + "oauth_issuer, oauth_audience, oauth_client_id, inbound_principal_issuer, "
            + "inbound_principal_client_id, tls_policy, tls_trust_reference, "
            + "connect_timeout_millis, response_timeout_millis, operation_timeout_millis, "
            + "max_response_bytes, max_decompressed_bytes, max_pages, max_resources, max_nesting_depth, "
            + "max_attempts, retry_class, created_at, updated_at) "
            + "VALUES (?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        versionId.value(),
        partnerId,
        version,
        configuration.organization().issuer().value(),
        configuration.organization().value(),
        configuration.endpointOrigin(),
        configuration.basePath(),
        authentication.method().name(),
        authentication.credentialReference().orElse(null),
        authentication.tokenSigningKeysReference().orElse(null),
        authentication.tokenOrigin().orElse(null),
        authentication.tokenPath().orElse(null),
        authentication.issuer().orElse(null),
        authentication.audience().orElse(null),
        authentication.clientId().orElse(null),
        configuration
            .inboundPrincipal()
            .map(value -> value.identity().issuer().value())
            .orElse(null),
        configuration.inboundPrincipal().map(value -> value.identity().value()).orElse(null),
        configuration.tlsPolicy().name(),
        configuration.tlsTrustReference().orElse(null),
        limits.connectTimeoutMillis(),
        limits.responseTimeoutMillis(),
        limits.operationTimeoutMillis(),
        limits.maxResponseBytes(),
        limits.maxDecompressedBytes(),
        limits.maxPages(),
        limits.maxResources(),
        limits.maxNestingDepth(),
        limits.maxAttempts(),
        configuration.retryClass().name(),
        Timestamp.from(now),
        Timestamp.from(now));
    jdbc.update(
        "UPDATE platform_exchange.partner_profile SET current_version = ? WHERE partner_id = ?",
        version,
        partnerId);
    for (PartnerTrustConfiguration.AllowedOperation operation : configuration.allowedOperations()) {
      jdbc.update(
          "INSERT INTO platform_exchange.partner_profile_operation "
              + "(profile_version_id, owner_context, operation_code, direction, protocol, profile) "
              + "VALUES (?, ?, ?, ?, ?, ?)",
          versionId.value(),
          operation.ownerContext().name(),
          operation.operationCode(),
          operation.direction().name(),
          operation.protocol().name(),
          operation.profile());
    }
    return requireVersion(configuration.partnerKey(), version);
  }

  private PartnerProfileVersion mapVersion(ResultSet row, int rowNumber) throws SQLException {
    UUID versionId = row.getObject("profile_version_id", UUID.class);
    PartnerTrustConfiguration.Authentication authentication =
        new PartnerTrustConfiguration.Authentication(
            PartnerTrustConfiguration.AuthenticationMethod.valueOf(
                row.getString("authentication_method")),
            Optional.ofNullable(row.getString("credential_reference")),
            Optional.ofNullable(row.getString("token_signing_keys_reference")),
            Optional.ofNullable(row.getString("token_origin")),
            Optional.ofNullable(row.getString("token_path")),
            Optional.ofNullable(row.getString("oauth_issuer")),
            Optional.ofNullable(row.getString("oauth_audience")),
            Optional.ofNullable(row.getString("oauth_client_id")));
    String inboundPrincipalClientId = row.getString("inbound_principal_client_id");
    String inboundPrincipalIssuer = row.getString("inbound_principal_issuer");
    Optional<ActorReference> inboundPrincipal =
        Optional.ofNullable(inboundPrincipalClientId)
            .map(
                clientId -> ActorReference.service(new IssuerId(inboundPrincipalIssuer), clientId));
    PartnerTrustConfiguration configuration =
        new PartnerTrustConfiguration(
            new PartnerKey(row.getString("partner_key")),
            new ExternalReference(
                new IssuerId(row.getString("organization_issuer")),
                "Organization",
                row.getString("organization_identifier")),
            row.getString("endpoint_origin"),
            row.getString("base_path"),
            authentication,
            PartnerTrustConfiguration.TlsPolicy.valueOf(row.getString("tls_policy")),
            Optional.ofNullable(row.getString("tls_trust_reference")),
            new PartnerTrustConfiguration.Limits(
                row.getInt("connect_timeout_millis"),
                row.getInt("response_timeout_millis"),
                row.getInt("operation_timeout_millis"),
                row.getInt("max_response_bytes"),
                row.getInt("max_decompressed_bytes"),
                row.getInt("max_pages"),
                row.getInt("max_resources"),
                row.getInt("max_nesting_depth"),
                row.getInt("max_attempts")),
            PartnerTrustConfiguration.RetryClass.valueOf(row.getString("retry_class")),
            operations(new InternalId(versionId)),
            inboundPrincipal);
    return new PartnerProfileVersion(
        new InternalId(versionId),
        row.getInt("version_number"),
        PartnerProfileState.valueOf(row.getString("state")),
        configuration,
        row.getTimestamp("created_at").toInstant(),
        row.getTimestamp("updated_at").toInstant());
  }

  private List<PartnerTrustConfiguration.AllowedOperation> operations(InternalId versionId) {
    return jdbc.query(
        "SELECT owner_context, operation_code, direction, protocol, profile "
            + "FROM platform_exchange.partner_profile_operation "
            + "WHERE profile_version_id = ? ORDER BY owner_context, operation_code, direction, profile",
        (row, rowNumber) ->
            new PartnerTrustConfiguration.AllowedOperation(
                PartnerTrustConfiguration.OwnerContext.valueOf(row.getString("owner_context")),
                row.getString("operation_code"),
                PartnerTrustConfiguration.Direction.valueOf(row.getString("direction")),
                PartnerTrustConfiguration.Protocol.valueOf(row.getString("protocol")),
                row.getString("profile")),
        versionId.value());
  }

  private PartnerProfileVersion requireVersion(PartnerKey partnerKey, int version) {
    return findVersion(partnerKey, version).orElseThrow(PartnerProfileNotFoundException::new);
  }

  private Optional<PartnerProfileVersion> findVersion(PartnerKey partnerKey, int version) {
    return jdbc
        .query(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM platform_exchange.partner_profile_version v "
                + "JOIN platform_exchange.partner_profile p ON p.partner_id = v.partner_id "
                + "WHERE p.partner_key = ? AND v.version_number = ?",
            this::mapVersion,
            partnerKey.value(),
            version)
        .stream()
        .findFirst();
  }

  private Optional<PartnerProfileVersion> findLifecycleVersion(
      PartnerKey partnerKey, String... states) {
    String statePredicate =
        states.length == 1
            ? "v.state = ?"
            : "v.state IN ("
                + String.join(", ", java.util.Collections.nCopies(states.length, "?"))
                + ")";
    Object[] parameters = new Object[states.length + 1];
    parameters[0] = partnerKey.value();
    System.arraycopy(states, 0, parameters, 1, states.length);
    return jdbc
        .query(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM platform_exchange.partner_profile_version v "
                + "JOIN platform_exchange.partner_profile p ON p.partner_id = v.partner_id "
                + "WHERE p.partner_key = ? AND "
                + statePredicate
                + " ORDER BY v.version_number DESC LIMIT 1",
            this::mapVersion,
            parameters)
        .stream()
        .findFirst();
  }

  private PartnerIdentity lockPartner(PartnerKey partnerKey) {
    return jdbc
        .query(
            "SELECT partner_id, current_version FROM platform_exchange.partner_profile "
                + "WHERE partner_key = ? FOR UPDATE",
            (row, rowNumber) ->
                new PartnerIdentity(
                    row.getObject("partner_id", UUID.class), row.getInt("current_version")),
            partnerKey.value())
        .stream()
        .findFirst()
        .orElseThrow(PartnerProfileNotFoundException::new);
  }

  private static void requireCurrentVersion(PartnerIdentity identity, int requestedVersion) {
    if (identity.currentVersion() != requestedVersion) {
      throw new PartnerProfileStateConflictException();
    }
  }

  private record PartnerIdentity(UUID id, int currentVersion) {}
}
