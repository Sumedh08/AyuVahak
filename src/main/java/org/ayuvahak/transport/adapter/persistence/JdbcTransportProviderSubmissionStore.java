package org.ayuvahak.transport.adapter.persistence;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.transport.application.TransportProviderSubmissionStore;
import org.ayuvahak.transport.domain.TransportProviderSubmission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for minimal Transport-owned provider submission metadata. */
@Repository
public class JdbcTransportProviderSubmissionStore implements TransportProviderSubmissionStore {

  private final JdbcTemplate jdbc;

  public JdbcTransportProviderSubmissionStore(JdbcTemplate jdbc) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "JDBC template");
  }

  @Override
  public Optional<TransportProviderSubmission> find(
      TransportProviderSubmission.OperationKind kind, InternalId operationReference) {
    return select(kind, operationReference, false);
  }

  @Override
  public Optional<TransportProviderSubmission> findForUpdate(
      TransportProviderSubmission.OperationKind kind, InternalId operationReference) {
    return select(kind, operationReference, true);
  }

  private Optional<TransportProviderSubmission> select(
      TransportProviderSubmission.OperationKind kind, InternalId operationReference, boolean lock) {
    List<TransportProviderSubmission> found =
        jdbc.query(
            "SELECT * FROM transport.provider_submission "
                + "WHERE operation_kind = ? AND operation_reference = ?"
                + (lock ? " FOR UPDATE" : ""),
            (row, index) -> map(row),
            kind.name(),
            operationReference.value());
    return found.stream().findFirst();
  }

  @Override
  public boolean insert(TransportProviderSubmission submission) {
    return jdbc.update(
            "INSERT INTO transport.provider_submission (operation_reference, transport_request_id, "
                + "operation_kind, partner_key, submission_state, configuration_version, response_status, "
                + "failure_code, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (operation_kind, operation_reference) DO NOTHING",
            submission.operationReference().value(),
            submission.transportRequestId().value(),
            submission.operationKind().name(),
            submission.partnerKey(),
            submission.state().name(),
            submission.configurationVersion().orElse(null),
            submission.responseStatus().orElse(null),
            submission.failureCode().orElse(null),
            Timestamp.from(submission.createdAt()),
            Timestamp.from(submission.updatedAt()),
            submission.version())
        == 1;
  }

  @Override
  public boolean transition(
      InternalId operationReference,
      TransportProviderSubmission.OperationKind kind,
      long expectedVersion,
      TransportProviderSubmission.State state,
      Optional<Integer> configurationVersion,
      Optional<Integer> responseStatus,
      Optional<String> failureCode,
      java.time.Instant updatedAt) {
    return jdbc.update(
            "UPDATE transport.provider_submission SET submission_state = ?, configuration_version = ?, "
                + "response_status = ?, failure_code = ?, updated_at = ?, version = version + 1 "
                + "WHERE operation_kind = ? AND operation_reference = ? AND version = ? "
                + "AND submission_state = 'ATTEMPTING'",
            state.name(),
            configurationVersion.orElse(null),
            responseStatus.orElse(null),
            failureCode.orElse(null),
            Timestamp.from(updatedAt),
            kind.name(),
            operationReference.value(),
            expectedVersion)
        == 1;
  }

  private static TransportProviderSubmission map(java.sql.ResultSet row)
      throws java.sql.SQLException {
    return new TransportProviderSubmission(
        InternalId.parse(row.getObject("operation_reference", UUID.class).toString()),
        InternalId.parse(row.getObject("transport_request_id", UUID.class).toString()),
        TransportProviderSubmission.OperationKind.valueOf(row.getString("operation_kind")),
        row.getString("partner_key"),
        TransportProviderSubmission.State.valueOf(row.getString("submission_state")),
        Optional.ofNullable(row.getObject("configuration_version", Integer.class)),
        Optional.ofNullable(row.getObject("response_status", Integer.class)),
        Optional.ofNullable(row.getString("failure_code")),
        row.getTimestamp("created_at").toInstant(),
        row.getTimestamp("updated_at").toInstant(),
        row.getLong("version"));
  }
}
