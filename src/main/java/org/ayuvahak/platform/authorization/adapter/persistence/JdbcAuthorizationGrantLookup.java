package org.ayuvahak.platform.authorization.adapter.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.ayuvahak.platform.authorization.application.AuthorizationGrantLookup;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC read adapter for the Identity & Access-owned active grants. */
public final class JdbcAuthorizationGrantLookup implements AuthorizationGrantLookup {

  private final JdbcTemplate jdbcTemplate;

  public JdbcAuthorizationGrantLookup(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JDBC template");
  }

  @Override
  public boolean hasActiveGrant(
      ActorReference actor, FunctionalAuthority authority, ExecutionScope scope) {
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(authority, "authority");
    Objects.requireNonNull(scope, "scope");

    StringBuilder sql =
        new StringBuilder(
            "SELECT EXISTS (SELECT 1 FROM identity_access.authorization_grant "
                + "WHERE principal_kind = ? AND principal_issuer = ? "
                + "AND principal_identifier_type = ? AND principal_identifier = ? "
                + "AND authority_code = ? AND grant_status = 'ACTIVE'");
    List<Object> parameters = new ArrayList<>();
    parameters.add(actor.kind().name());
    parameters.add(actor.identity().issuer().value());
    parameters.add(actor.identity().resourceType());
    parameters.add(actor.identity().value());
    parameters.add(authority.code());

    switch (scope.kind()) {
      case PLATFORM -> sql.append(" AND scope_kind = 'PLATFORM'");
      case ORGANIZATION -> {
        var organization = scope.organization().orElseThrow();
        sql.append(
            " AND scope_kind = 'ORGANIZATION' AND organization_issuer = ? "
                + "AND organization_identifier = ?");
        parameters.add(organization.issuer().value());
        parameters.add(organization.value());
      }
      case FACILITY -> {
        var organization = scope.organization().orElseThrow();
        var facility = scope.facility().orElseThrow();
        sql.append(
            " AND scope_kind = 'FACILITY' AND organization_issuer = ? "
                + "AND organization_identifier = ? AND facility_issuer = ? "
                + "AND facility_identifier = ?");
        parameters.add(organization.issuer().value());
        parameters.add(organization.value());
        parameters.add(facility.issuer().value());
        parameters.add(facility.value());
      }
    }
    sql.append(")");

    Boolean found =
        jdbcTemplate.queryForObject(sql.toString(), Boolean.class, parameters.toArray());
    return Boolean.TRUE.equals(found);
  }
}
