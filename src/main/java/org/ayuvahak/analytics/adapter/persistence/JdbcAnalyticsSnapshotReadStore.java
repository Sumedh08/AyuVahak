package org.ayuvahak.analytics.adapter.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.application.AnalyticsSnapshotReadStore;
import org.ayuvahak.analytics.application.AnalyticsSnapshotSummary;
import org.ayuvahak.analytics.domain.AggregateCompleteness;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Read-only Analytics schema projection for scope-authorized aggregate snapshot summaries. */
@Repository
public final class JdbcAnalyticsSnapshotReadStore implements AnalyticsSnapshotReadStore {

  private static final RowMapper<AnalyticsSnapshotSummary> SUMMARY_MAPPER =
      JdbcAnalyticsSnapshotReadStore::mapSummary;

  private final JdbcTemplate jdbc;

  public JdbcAnalyticsSnapshotReadStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<AnalyticsSnapshotSummary> findRecent(
      ExternalReference organization, Optional<ExternalReference> facility, int offset, int limit) {
    UUID organizationId = InternalId.parse(organization.value()).value();
    String facilityPredicate;
    Object[] scopeParameters;
    if (facility.isPresent()) {
      UUID facilityId = InternalId.parse(facility.orElseThrow().value()).value();
      facilityPredicate = "s.facility_id = ?";
      scopeParameters = new Object[] {organizationId, facilityId};
    } else {
      // Organization grants see organization-level results only; facility details require a
      // facility-scoped grant and query.
      facilityPredicate = "s.facility_id IS NULL";
      scopeParameters = new Object[] {organizationId};
    }
    String sql =
        "SELECT s.snapshot_id, s.indicator_key, s.definition_version, s.period_start_local, "
            + "s.period_end_exclusive_local, s.time_zone_id, s.completeness, "
            + "COALESCE(s.aggregate_decimal_value, s.aggregate_value::numeric) AS result_value, "
            + "s.computed_at, r.revision_number, series.state AS revision_state, "
            + "export.operation_state AS export_state, export.result_class AS export_result_class "
            + "FROM analytics.aggregate_snapshot s "
            + "LEFT JOIN analytics.aggregate_snapshot_revision r ON r.snapshot_id = s.snapshot_id "
            + "LEFT JOIN analytics.aggregate_snapshot_series series ON series.series_id = r.series_id "
            + "LEFT JOIN LATERAL (SELECT e.operation_state, e.result_class "
            + "FROM analytics.dhis2_export_operation e WHERE e.snapshot_id = s.snapshot_id "
            + "ORDER BY e.updated_at DESC, e.created_at DESC, e.operation_id DESC LIMIT 1) export ON TRUE "
            + "WHERE s.organization_id = ? AND "
            + facilityPredicate
            + " ORDER BY s.computed_at DESC, s.snapshot_id DESC LIMIT ? OFFSET ?";
    Object[] parameters = new Object[scopeParameters.length + 2];
    System.arraycopy(scopeParameters, 0, parameters, 0, scopeParameters.length);
    parameters[scopeParameters.length] = limit;
    parameters[scopeParameters.length + 1] = offset;
    return jdbc.query(sql, SUMMARY_MAPPER, parameters);
  }

  private static AnalyticsSnapshotSummary mapSummary(ResultSet result, int rowNumber)
      throws SQLException {
    BigDecimal value = result.getBigDecimal("result_value");
    int revisionNumber = result.getInt("revision_number");
    boolean hasRevision = !result.wasNull();
    return new AnalyticsSnapshotSummary(
        result.getObject("snapshot_id", UUID.class),
        result.getString("indicator_key"),
        result.getInt("definition_version"),
        result.getObject("period_start_local", LocalDate.class),
        result.getObject("period_end_exclusive_local", LocalDate.class),
        result.getString("time_zone_id"),
        AggregateCompleteness.valueOf(result.getString("completeness")),
        Optional.ofNullable(value),
        result.getTimestamp("computed_at").toInstant(),
        hasRevision ? Optional.of(revisionNumber) : Optional.empty(),
        Optional.ofNullable(result.getString("revision_state")),
        Optional.ofNullable(result.getString("export_state")),
        Optional.ofNullable(result.getString("export_result_class")));
  }
}
