package org.ayuvahak.platform.authorization.adapter.persistence;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.authorization.application.GrantedSubjectCapability;
import org.ayuvahak.platform.authorization.application.SubjectCapabilityLookup;
import org.ayuvahak.platform.authorization.domain.GrantScope;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Identity & Access query restricted to active grants for the caller's validated actor identity.
 */
public final class JdbcSubjectCapabilityLookup implements SubjectCapabilityLookup {

  private final JdbcTemplate jdbc;

  public JdbcSubjectCapabilityLookup(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public List<GrantedSubjectCapability> findActiveFor(ActorReference actor) {
    Objects.requireNonNull(actor, "actor");
    return jdbc.query(
        "SELECT authority_code, scope_kind, organization_issuer, "
            + "organization_identifier_type, organization_identifier, facility_issuer, "
            + "facility_identifier_type, facility_identifier "
            + "FROM identity_access.authorization_grant "
            + "WHERE principal_kind = ? AND principal_issuer = ? "
            + "AND principal_identifier_type = ? AND principal_identifier = ? "
            + "AND grant_status = 'ACTIVE' "
            + "ORDER BY authority_code, scope_kind, organization_issuer, "
            + "organization_identifier_type, organization_identifier, facility_issuer, "
            + "facility_identifier_type, facility_identifier",
        (result, rowNumber) ->
            new GrantedSubjectCapability(
                result.getString("authority_code"),
                new GrantScope(
                    ExecutionScope.Kind.valueOf(result.getString("scope_kind")),
                    reference(
                        result.getString("organization_issuer"),
                        result.getString("organization_identifier_type"),
                        result.getString("organization_identifier")),
                    reference(
                        result.getString("facility_issuer"),
                        result.getString("facility_identifier_type"),
                        result.getString("facility_identifier")))),
        actor.kind().name(),
        actor.identity().issuer().value(),
        actor.identity().resourceType(),
        actor.identity().value());
  }

  private static Optional<ExternalReference> reference(String issuer, String type, String value) {
    if (issuer == null && type == null && value == null) {
      return Optional.empty();
    }
    if (issuer == null || type == null || value == null) {
      throw new IllegalStateException("stored grant scope reference is incomplete");
    }
    return Optional.of(new ExternalReference(new IssuerId(issuer), type, value));
  }
}
