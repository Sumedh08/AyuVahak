package org.ayuvahak.platform.audit.adapter.persistence;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.audit.application.AuditRecordPosition;
import org.ayuvahak.platform.audit.application.AuditRecordQuery;
import org.ayuvahak.platform.audit.application.AuditRecordView;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Restricted projection over Audit-owned records; it never selects payload or credential data. */
@Repository
@Lazy
public class JdbcAuditRecordQuery implements AuditRecordQuery {

  private static final String COLUMNS =
      "id, action_kind, actor_kind, actor_issuer, actor_reference, scope_kind, "
          + "organization_issuer, organization_reference, facility_issuer, facility_reference, "
          + "action_code, target_type, target_id, occurred_at, recorded_at, outcome, reason_code, "
          + "authentication_evidence, authorization_outcome, correlation_id, target_scope_kind, "
          + "target_organization_issuer, target_organization_reference, "
          + "target_facility_issuer, target_facility_reference";

  private final JdbcTemplate jdbc;

  public JdbcAuditRecordQuery(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public List<AuditRecordView> findPage(Optional<AuditRecordPosition> before, int limit) {
    Objects.requireNonNull(before, "before");
    if (limit < 1 || limit > 101) {
      throw new IllegalArgumentException("audit page query limit must be between 1 and 101");
    }
    String sql =
        "SELECT "
            + COLUMNS
            + " FROM audit.audit_record "
            + (before.isPresent() ? "WHERE (recorded_at, id) < (?, ?) " : "")
            + "ORDER BY recorded_at DESC, id DESC LIMIT ?";
    if (before.isEmpty()) {
      return jdbc.query(sql, (result, row) -> map(result), limit);
    }
    AuditRecordPosition position = before.orElseThrow();
    return jdbc.query(
        sql,
        (result, row) -> map(result),
        Timestamp.from(position.recordedAt()),
        position.id(),
        limit);
  }

  private static AuditRecordView map(java.sql.ResultSet result) throws java.sql.SQLException {
    return new AuditRecordView(
        result.getObject("id", java.util.UUID.class),
        result.getString("action_kind"),
        result.getString("actor_kind"),
        result.getString("actor_issuer"),
        result.getString("actor_reference"),
        result.getString("scope_kind"),
        result.getString("organization_issuer"),
        result.getString("organization_reference"),
        result.getString("facility_issuer"),
        result.getString("facility_reference"),
        result.getString("action_code"),
        result.getString("target_type"),
        result.getObject("target_id", java.util.UUID.class),
        result.getTimestamp("occurred_at").toInstant(),
        result.getTimestamp("recorded_at").toInstant(),
        result.getString("outcome"),
        result.getString("reason_code"),
        result.getString("authentication_evidence"),
        result.getString("authorization_outcome"),
        result.getString("correlation_id"),
        result.getString("target_scope_kind"),
        result.getString("target_organization_issuer"),
        result.getString("target_organization_reference"),
        result.getString("target_facility_issuer"),
        result.getString("target_facility_reference"));
  }
}
