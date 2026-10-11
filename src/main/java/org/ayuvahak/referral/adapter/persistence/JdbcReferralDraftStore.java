package org.ayuvahak.referral.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.platform.idempotency.configuration.CommandIdempotencyProperties;
import org.ayuvahak.referral.application.ReferralDraftStore;
import org.ayuvahak.referral.application.ReferralListItem;
import org.ayuvahak.referral.application.ReferralReadFilter;
import org.ayuvahak.referral.domain.CareRequirement;
import org.ayuvahak.referral.domain.PatientReference;
import org.ayuvahak.referral.domain.ReferralCancellation;
import org.ayuvahak.referral.domain.ReferralDraft;
import org.ayuvahak.referral.domain.ReferralResolution;
import org.ayuvahak.referral.domain.ReferralResolutionOutcome;
import org.ayuvahak.referral.domain.ReferralState;
import org.ayuvahak.referral.domain.SubmittedReferralRevision;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for Referral-owned draft and command-receipt state. */
@Repository
@Lazy
public class JdbcReferralDraftStore implements ReferralDraftStore {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String DRAFT_COLUMNS =
      "referral_id, origin_organization_id, origin_facility_id, patient_issuer, "
          + "patient_resource_type, patient_identifier, encounter_issuer, "
          + "encounter_resource_type, encounter_identifier, requested_start, requested_end, "
          + "selected_destination_organization_id, priority_code, "
          + "created_by_kind, created_by_issuer, created_by_identifier_type, "
          + "created_by_identifier, updated_by_kind, updated_by_issuer, "
          + "updated_by_identifier_type, updated_by_identifier, state, version, created_at, updated_at";

  private final JdbcTemplate jdbc;
  private final CommandIdempotencyProperties idempotencyProperties;

  public JdbcReferralDraftStore(
      JdbcTemplate jdbc, CommandIdempotencyProperties idempotencyProperties) {
    this.jdbc = jdbc;
    this.idempotencyProperties = idempotencyProperties;
  }

  @Override
  public void insertDraft(ReferralDraft draft) {
    jdbc.update(
        "INSERT INTO referral.draft (referral_id, state, origin_organization_id, "
            + "origin_facility_id, patient_issuer, patient_resource_type, patient_identifier, "
            + "encounter_issuer, encounter_resource_type, encounter_identifier, requested_start, "
            + "requested_end, selected_destination_organization_id, priority_code, created_by_kind, "
            + "created_by_issuer, created_by_identifier_type, created_by_identifier, "
            + "updated_by_kind, updated_by_issuer, updated_by_identifier_type, updated_by_identifier, "
            + "version, created_at, updated_at) VALUES (?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        draft.id().value(),
        directoryId(draft.originOrganization()),
        draft.originFacility().map(JdbcReferralDraftStore::directoryId).orElse(null),
        draft.patientReference().patient().issuer().value(),
        draft.patientReference().patient().resourceType(),
        draft.patientReference().patient().value(),
        draft
            .patientReference()
            .encounterOrCase()
            .map(reference -> reference.issuer().value())
            .orElse(null),
        draft
            .patientReference()
            .encounterOrCase()
            .map(ExternalReference::resourceType)
            .orElse(null),
        draft.patientReference().encounterOrCase().map(ExternalReference::value).orElse(null),
        draft.careRequirement().requestedStart().map(Timestamp::from).orElse(null),
        draft.careRequirement().requestedEnd().map(Timestamp::from).orElse(null),
        draft
            .careRequirement()
            .selectedDestinationOrganization()
            .map(JdbcReferralDraftStore::directoryId)
            .orElse(null),
        draft.careRequirement().priorityCode().orElse(null),
        draft.createdBy().kind().name(),
        draft.createdBy().identity().issuer().value(),
        draft.createdBy().identity().resourceType(),
        draft.createdBy().identity().value(),
        draft.updatedBy().kind().name(),
        draft.updatedBy().identity().issuer().value(),
        draft.updatedBy().identity().resourceType(),
        draft.updatedBy().identity().value(),
        draft.version(),
        Timestamp.from(draft.createdAt()),
        Timestamp.from(draft.updatedAt()));
    insertCodes(draft.id(), draft.careRequirement());
  }

  @Override
  public Optional<ReferralDraft> lockDraft(InternalId referralId) {
    List<UUID> ids =
        jdbc.query(
            "SELECT referral_id FROM referral.draft WHERE referral_id = ? FOR UPDATE",
            (result, row) -> result.getObject("referral_id", UUID.class),
            referralId.value());
    return ids.stream().findFirst().flatMap(id -> loadDraft(new InternalId(id)));
  }

  @Override
  public Optional<ReferralDraft> findDraft(InternalId referralId) {
    List<UUID> ids =
        jdbc.query(
            "SELECT referral_id FROM referral.draft WHERE referral_id = ?",
            (result, row) -> result.getObject("referral_id", UUID.class),
            referralId.value());
    return ids.stream().findFirst().flatMap(id -> loadDraft(new InternalId(id)));
  }

  @Override
  public boolean updateDraft(ReferralDraft revised, long expectedVersion) {
    return updateCurrentContent(revised, expectedVersion, ReferralState.DRAFT);
  }

  @Override
  public boolean submitDraft(
      ReferralDraft submitted, long expectedVersion, SubmittedReferralRevision revision) {
    int changed =
        jdbc.update(
            "UPDATE referral.draft SET state = 'SUBMITTED', updated_by_kind = ?, "
                + "updated_by_issuer = ?, updated_by_identifier_type = ?, updated_by_identifier = ?, "
                + "version = ?, updated_at = ? WHERE referral_id = ? AND version = ? AND state = 'DRAFT'",
            submitted.updatedBy().kind().name(),
            submitted.updatedBy().identity().issuer().value(),
            submitted.updatedBy().identity().resourceType(),
            submitted.updatedBy().identity().value(),
            submitted.version(),
            Timestamp.from(submitted.updatedAt()),
            submitted.id().value(),
            expectedVersion);
    if (changed != 1) {
      return false;
    }
    insertSubmittedRevision(revision);
    return true;
  }

  @Override
  public boolean amendSubmitted(
      ReferralDraft amended, long expectedVersion, SubmittedReferralRevision revision) {
    if (!updateCurrentContent(amended, expectedVersion, ReferralState.SUBMITTED)) {
      return false;
    }
    insertSubmittedRevision(revision);
    return true;
  }

  @Override
  public boolean resolveDraft(
      ReferralDraft resolved, long expectedVersion, ReferralResolution resolution) {
    int changed =
        jdbc.update(
            "UPDATE referral.draft SET state = 'RESOLVED', updated_by_kind = ?, "
                + "updated_by_issuer = ?, updated_by_identifier_type = ?, updated_by_identifier = ?, "
                + "version = ?, updated_at = ? WHERE referral_id = ? AND version = ? AND state = 'SUBMITTED'",
            resolved.updatedBy().kind().name(),
            resolved.updatedBy().identity().issuer().value(),
            resolved.updatedBy().identity().resourceType(),
            resolved.updatedBy().identity().value(),
            resolved.version(),
            Timestamp.from(resolved.updatedAt()),
            resolved.id().value(),
            expectedVersion);
    if (changed != 1) {
      return false;
    }
    jdbc.update(
        "INSERT INTO referral.resolution (referral_id, outcome, reason_code, resolved_by_kind, "
            + "resolved_by_issuer, resolved_by_identifier_type, resolved_by_identifier, "
            + "referral_version, resolved_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        resolution.referralId().value(),
        resolution.outcome().name(),
        resolution.reasonCode(),
        resolution.resolvedBy().kind().name(),
        resolution.resolvedBy().identity().issuer().value(),
        resolution.resolvedBy().identity().resourceType(),
        resolution.resolvedBy().identity().value(),
        resolution.referralVersion(),
        Timestamp.from(resolution.resolvedAt()));
    return true;
  }

  @Override
  public boolean cancelDraft(
      ReferralDraft cancelled, long expectedVersion, ReferralCancellation cancellation) {
    int changed =
        jdbc.update(
            "UPDATE referral.draft SET state = 'CANCELLED', updated_by_kind = ?, "
                + "updated_by_issuer = ?, updated_by_identifier_type = ?, updated_by_identifier = ?, "
                + "version = ?, updated_at = ? WHERE referral_id = ? AND version = ? "
                + "AND state IN ('DRAFT', 'SUBMITTED')",
            cancelled.updatedBy().kind().name(),
            cancelled.updatedBy().identity().issuer().value(),
            cancelled.updatedBy().identity().resourceType(),
            cancelled.updatedBy().identity().value(),
            cancelled.version(),
            Timestamp.from(cancelled.updatedAt()),
            cancelled.id().value(),
            expectedVersion);
    if (changed != 1) {
      return false;
    }
    jdbc.update(
        "INSERT INTO referral.cancellation (referral_id, reason_code, cancelled_by_kind, "
            + "cancelled_by_issuer, cancelled_by_identifier_type, cancelled_by_identifier, "
            + "referral_version, cancelled_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        cancellation.referralId().value(),
        cancellation.reasonCode(),
        cancellation.cancelledBy().kind().name(),
        cancellation.cancelledBy().identity().issuer().value(),
        cancellation.cancelledBy().identity().resourceType(),
        cancellation.cancelledBy().identity().value(),
        cancellation.referralVersion(),
        Timestamp.from(cancellation.cancelledAt()));
    return true;
  }

  @Override
  public Optional<SubmittedReferralRevision> findSubmittedRevision(
      InternalId referralId, long referralVersion) {
    return findRevision("referral_version = ?", referralId, referralVersion);
  }

  @Override
  public Optional<SubmittedReferralRevision> findSubmittedRevisionNumber(
      InternalId referralId, long revisionNumber) {
    return findRevision("revision_number = ?", referralId, revisionNumber);
  }

  @Override
  public Optional<SubmittedReferralRevision> findLatestSubmittedRevision(InternalId referralId) {
    List<RevisionRow> rows =
        jdbc.query(
            revisionSelect() + "WHERE referral_id = ? ORDER BY revision_number DESC LIMIT 1",
            JdbcReferralDraftStore::revisionRow,
            referralId.value());
    return rows.stream().findFirst().map(this::toRevision);
  }

  @Override
  public List<ReferralListItem> findReferralPage(
      ExecutionScope scope, ReferralReadFilter filter, int limit, long offset) {
    StringBuilder query =
        new StringBuilder(
            "SELECT d.referral_id, d.state, d.version, d.origin_organization_id, "
                + "d.origin_facility_id, latest.submitted_at, "
                + "COALESCE(latest.submitted_at, d.created_at) AS relevant_at "
                + "FROM referral.draft d LEFT JOIN LATERAL ("
                + "SELECT MAX(r.submitted_at) AS submitted_at FROM referral.submitted_revision r "
                + "WHERE r.referral_id = d.referral_id) latest ON TRUE "
                + "WHERE d.origin_organization_id = ? ");
    List<Object> parameters = new java.util.ArrayList<>();
    parameters.add(directoryId(scope.organization().orElseThrow()));
    if (scope.kind() == ExecutionScope.Kind.FACILITY) {
      query.append("AND d.origin_facility_id = ? ");
      parameters.add(directoryId(scope.facility().orElseThrow()));
    }
    filter
        .state()
        .ifPresent(
            state -> {
              query.append("AND d.state = ? ");
              parameters.add(state.name());
            });
    filter
        .fromInclusive()
        .ifPresent(
            from -> {
              query.append("AND COALESCE(latest.submitted_at, d.created_at) >= ? ");
              parameters.add(Timestamp.from(from));
            });
    filter
        .untilExclusive()
        .ifPresent(
            until -> {
              query.append("AND COALESCE(latest.submitted_at, d.created_at) < ? ");
              parameters.add(Timestamp.from(until));
            });
    query.append("ORDER BY relevant_at DESC, d.referral_id ASC LIMIT ? OFFSET ?");
    parameters.add(limit);
    parameters.add(offset);
    return jdbc.query(
        query.toString(),
        (result, row) ->
            new ReferralListItem(
                new InternalId(result.getObject("referral_id", UUID.class)),
                ReferralState.valueOf(result.getString("state")),
                result.getLong("version"),
                directoryReference(
                    "Organization", result.getObject("origin_organization_id", UUID.class)),
                Optional.ofNullable(result.getObject("origin_facility_id", UUID.class))
                    .map(value -> directoryReference("Facility", value)),
                result.getTimestamp("relevant_at").toInstant(),
                Optional.ofNullable(result.getTimestamp("submitted_at")).map(Timestamp::toInstant)),
        parameters.toArray());
  }

  private Optional<SubmittedReferralRevision> findRevision(
      String selector, InternalId referralId, long value) {
    List<RevisionRow> rows =
        jdbc.query(
            revisionSelect() + "WHERE referral_id = ? AND " + selector,
            JdbcReferralDraftStore::revisionRow,
            referralId.value(),
            value);
    return rows.stream().findFirst().map(this::toRevision);
  }

  private static String revisionSelect() {
    return "SELECT referral_id, revision_number, referral_version, origin_organization_id, "
        + "origin_facility_id, patient_issuer, patient_resource_type, patient_identifier, "
        + "encounter_issuer, encounter_resource_type, encounter_identifier, requested_start, "
        + "requested_end, selected_destination_organization_id, priority_code, "
        + "source_actor_kind, source_actor_issuer, source_actor_identifier_type, "
        + "source_actor_identifier, submitted_by_kind, submitted_by_issuer, "
        + "submitted_by_identifier_type, submitted_by_identifier, submitted_at, reason_code "
        + "FROM referral.submitted_revision ";
  }

  private boolean updateCurrentContent(
      ReferralDraft revised, long expectedVersion, ReferralState expectedState) {
    int changed =
        jdbc.update(
            "UPDATE referral.draft SET patient_issuer = ?, patient_resource_type = ?, "
                + "patient_identifier = ?, encounter_issuer = ?, encounter_resource_type = ?, "
                + "encounter_identifier = ?, requested_start = ?, requested_end = ?, "
                + "selected_destination_organization_id = ?, priority_code = ?, updated_by_kind = ?, "
                + "updated_by_issuer = ?, updated_by_identifier_type = ?, updated_by_identifier = ?, "
                + "version = ?, updated_at = ? WHERE referral_id = ? AND version = ? AND state = ?",
            revised.patientReference().patient().issuer().value(),
            revised.patientReference().patient().resourceType(),
            revised.patientReference().patient().value(),
            revised
                .patientReference()
                .encounterOrCase()
                .map(reference -> reference.issuer().value())
                .orElse(null),
            revised
                .patientReference()
                .encounterOrCase()
                .map(ExternalReference::resourceType)
                .orElse(null),
            revised.patientReference().encounterOrCase().map(ExternalReference::value).orElse(null),
            revised.careRequirement().requestedStart().map(Timestamp::from).orElse(null),
            revised.careRequirement().requestedEnd().map(Timestamp::from).orElse(null),
            revised
                .careRequirement()
                .selectedDestinationOrganization()
                .map(JdbcReferralDraftStore::directoryId)
                .orElse(null),
            revised.careRequirement().priorityCode().orElse(null),
            revised.updatedBy().kind().name(),
            revised.updatedBy().identity().issuer().value(),
            revised.updatedBy().identity().resourceType(),
            revised.updatedBy().identity().value(),
            revised.version(),
            Timestamp.from(revised.updatedAt()),
            revised.id().value(),
            expectedVersion,
            expectedState.name());
    if (changed != 1) {
      return false;
    }
    jdbc.update(
        "DELETE FROM referral.required_care_capability WHERE referral_id = ?",
        revised.id().value());
    jdbc.update(
        "DELETE FROM referral.permitted_geography_code WHERE referral_id = ?",
        revised.id().value());
    insertCodes(revised.id(), revised.careRequirement());
    return true;
  }

  private void insertSubmittedRevision(SubmittedReferralRevision revision) {
    jdbc.update(
        "INSERT INTO referral.submitted_revision (referral_id, revision_number, referral_version, "
            + "origin_organization_id, origin_facility_id, patient_issuer, patient_resource_type, "
            + "patient_identifier, encounter_issuer, encounter_resource_type, encounter_identifier, "
            + "requested_start, requested_end, selected_destination_organization_id, priority_code, "
            + "source_actor_kind, source_actor_issuer, source_actor_identifier_type, "
            + "source_actor_identifier, submitted_by_kind, submitted_by_issuer, "
            + "submitted_by_identifier_type, submitted_by_identifier, submitted_at, reason_code) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        revision.referralId().value(),
        revision.revisionNumber(),
        revision.referralVersion(),
        directoryId(revision.originOrganization()),
        revision.originFacility().map(JdbcReferralDraftStore::directoryId).orElse(null),
        revision.patientReference().patient().issuer().value(),
        revision.patientReference().patient().resourceType(),
        revision.patientReference().patient().value(),
        revision
            .patientReference()
            .encounterOrCase()
            .map(reference -> reference.issuer().value())
            .orElse(null),
        revision
            .patientReference()
            .encounterOrCase()
            .map(ExternalReference::resourceType)
            .orElse(null),
        revision.patientReference().encounterOrCase().map(ExternalReference::value).orElse(null),
        revision.careRequirement().requestedStart().map(Timestamp::from).orElse(null),
        revision.careRequirement().requestedEnd().map(Timestamp::from).orElse(null),
        revision
            .careRequirement()
            .selectedDestinationOrganization()
            .map(JdbcReferralDraftStore::directoryId)
            .orElse(null),
        revision.careRequirement().priorityCode().orElse(null),
        revision.sourceActor().kind().name(),
        revision.sourceActor().identity().issuer().value(),
        revision.sourceActor().identity().resourceType(),
        revision.sourceActor().identity().value(),
        revision.submittedBy().kind().name(),
        revision.submittedBy().identity().issuer().value(),
        revision.submittedBy().identity().resourceType(),
        revision.submittedBy().identity().value(),
        Timestamp.from(revision.submittedAt()),
        revision.reasonCode().orElse(null));
    for (String code : revision.careRequirement().requiredCareCapabilityCodes()) {
      jdbc.update(
          "INSERT INTO referral.submitted_revision_required_care_capability "
              + "(referral_id, revision_number, capability_code) VALUES (?, ?, ?)",
          revision.referralId().value(),
          revision.revisionNumber(),
          code);
    }
    for (String code : revision.careRequirement().permittedGeographyCodes()) {
      jdbc.update(
          "INSERT INTO referral.submitted_revision_permitted_geography_code "
              + "(referral_id, revision_number, geography_code) VALUES (?, ?, ?)",
          revision.referralId().value(),
          revision.revisionNumber(),
          code);
    }
  }

  @Override
  public Optional<ReferralResolution> findResolution(InternalId referralId) {
    List<ReferralResolution> resolutions =
        jdbc.query(
            "SELECT referral_id, outcome, reason_code, resolved_by_kind, resolved_by_issuer, "
                + "resolved_by_identifier_type, resolved_by_identifier, referral_version, resolved_at "
                + "FROM referral.resolution WHERE referral_id = ?",
            (result, row) ->
                new ReferralResolution(
                    new InternalId(result.getObject("referral_id", UUID.class)),
                    ReferralResolutionOutcome.valueOf(result.getString("outcome")),
                    result.getString("reason_code"),
                    actor(result, "resolved_by"),
                    result.getTimestamp("resolved_at").toInstant(),
                    result.getLong("referral_version")),
            referralId.value());
    return resolutions.stream().findFirst();
  }

  @Override
  public Optional<ReferralCancellation> findCancellation(InternalId referralId) {
    return jdbc
        .query(
            "SELECT referral_id, reason_code, cancelled_by_kind, cancelled_by_issuer, "
                + "cancelled_by_identifier_type, cancelled_by_identifier, referral_version, cancelled_at "
                + "FROM referral.cancellation WHERE referral_id = ?",
            (result, row) ->
                new ReferralCancellation(
                    new InternalId(result.getObject("referral_id", UUID.class)),
                    result.getString("reason_code"),
                    actor(result, "cancelled_by"),
                    result.getTimestamp("cancelled_at").toInstant(),
                    result.getLong("referral_version")),
            referralId.value())
        .stream()
        .findFirst();
  }

  @Override
  public boolean claimConsumerEvent(InternalId eventId, EventType eventType, Instant recordedAt) {
    return jdbc.update(
            "INSERT INTO referral.consumer_event_receipt "
                + "(event_id, event_type, event_state, recorded_at) "
                + "VALUES (?, ?, 'PROCESSING', ?) ON CONFLICT (event_id) DO NOTHING",
            eventId.value(),
            eventType.toString(),
            Timestamp.from(recordedAt))
        == 1;
  }

  @Override
  public void completeConsumerEvent(
      InternalId eventId, InternalId referralId, String outcome, Instant recordedAt) {
    int changed =
        jdbc.update(
            "UPDATE referral.consumer_event_receipt SET event_state = 'COMPLETED', "
                + "referral_id = ?, outcome = ?, recorded_at = ? "
                + "WHERE event_id = ? AND event_state = 'PROCESSING'",
            referralId.value(),
            outcome,
            Timestamp.from(recordedAt),
            eventId.value());
    if (changed != 1) {
      throw new IllegalStateException("Referral event consumer receipt could not be completed");
    }
  }

  @Override
  public ReceiptClaim claimReceipt(
      CommandReceiptKey key, RequestFingerprint fingerprint, Instant recordedAt) {
    int inserted =
        jdbc.update(
            "INSERT INTO referral.command_receipt (scope_digest, idempotency_key_digest, "
                + "request_fingerprint, receipt_state, recorded_at) VALUES (?, ?, ?, 'IN_PROGRESS', ?) "
                + "ON CONFLICT DO NOTHING",
            key.scopeDigest(),
            key.idempotencyKey().value(),
            fingerprint.value(),
            Timestamp.from(recordedAt));
    if (inserted == 1) {
      return new ReceiptClaim(
          ReceiptState.OWNER, Optional.empty(), Optional.empty(), Optional.empty());
    }
    return jdbc.queryForObject(
        "SELECT request_fingerprint, receipt_state, response_status, result_reference, result_version "
            + "FROM referral.command_receipt WHERE scope_digest = ? AND idempotency_key_digest = ?",
        (result, row) -> {
          if (!fingerprint.value().equals(result.getString("request_fingerprint"))) {
            return new ReceiptClaim(
                ReceiptState.KEY_REUSED, Optional.empty(), Optional.empty(), Optional.empty());
          }
          if ("IN_PROGRESS".equals(result.getString("receipt_state"))) {
            return new ReceiptClaim(
                ReceiptState.IN_PROGRESS, Optional.empty(), Optional.empty(), Optional.empty());
          }
          return new ReceiptClaim(
              ReceiptState.REPLAY,
              Optional.of(result.getInt("response_status")),
              Optional.of(new InternalId(result.getObject("result_reference", UUID.class))),
              Optional.of(result.getLong("result_version")));
        },
        key.scopeDigest(),
        key.idempotencyKey().value());
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
      CommandReceiptKey key,
      int responseStatus,
      InternalId resultReference,
      long resultVersion,
      Instant recordedAt) {
    int changed =
        jdbc.update(
            "UPDATE referral.command_receipt SET receipt_state = 'COMPLETED', response_status = ?, "
                + "result_reference = ?, result_version = ?, recorded_at = ? "
                + "WHERE scope_digest = ? AND idempotency_key_digest = ? AND receipt_state = 'IN_PROGRESS'",
            responseStatus,
            resultReference.value(),
            resultVersion,
            Timestamp.from(recordedAt),
            key.scopeDigest(),
            key.idempotencyKey().value());
    if (changed != 1) {
      throw new IllegalStateException("Referral command receipt could not be completed");
    }
  }

  private Optional<ReferralDraft> loadDraft(InternalId id) {
    List<DraftRow> rows =
        jdbc.query(
            "SELECT " + DRAFT_COLUMNS + " FROM referral.draft WHERE referral_id = ?",
            JdbcReferralDraftStore::draftRow,
            id.value());
    return rows.stream().findFirst().map(this::toDraft);
  }

  private ReferralDraft toDraft(DraftRow row) {
    ExternalReference patient =
        new ExternalReference(
            new IssuerId(row.patientIssuer()), row.patientResourceType(), row.patientIdentifier());
    Optional<ExternalReference> encounter =
        row.encounterIssuer() == null
            ? Optional.empty()
            : Optional.of(
                new ExternalReference(
                    new IssuerId(row.encounterIssuer()),
                    row.encounterResourceType(),
                    row.encounterIdentifier()));
    CareRequirement requirement =
        new CareRequirement(
            readCodes("referral.required_care_capability", "capability_code", row.id()),
            Optional.ofNullable(row.requestedStart()).map(Timestamp::toInstant),
            Optional.ofNullable(row.requestedEnd()).map(Timestamp::toInstant),
            readCodes("referral.permitted_geography_code", "geography_code", row.id()),
            Optional.ofNullable(row.destinationOrganizationId())
                .map(value -> directoryReference("Organization", value)),
            Optional.ofNullable(row.priorityCode()));
    return new ReferralDraft(
        row.id(),
        directoryReference("Organization", row.organizationId()),
        Optional.ofNullable(row.facilityId()).map(value -> directoryReference("Facility", value)),
        new PatientReference(patient, encounter),
        requirement,
        row.createdBy(),
        row.updatedBy(),
        row.state(),
        row.version(),
        row.createdAt().toInstant(),
        row.updatedAt().toInstant());
  }

  private static DraftRow draftRow(ResultSet result, int row) throws SQLException {
    InternalId id = new InternalId(result.getObject("referral_id", UUID.class));
    return new DraftRow(
        id,
        result.getObject("origin_organization_id", UUID.class),
        result.getObject("origin_facility_id", UUID.class),
        result.getString("patient_issuer"),
        result.getString("patient_resource_type"),
        result.getString("patient_identifier"),
        result.getString("encounter_issuer"),
        result.getString("encounter_resource_type"),
        result.getString("encounter_identifier"),
        result.getTimestamp("requested_start"),
        result.getTimestamp("requested_end"),
        result.getObject("selected_destination_organization_id", UUID.class),
        result.getString("priority_code"),
        actor(result, "created_by"),
        actor(result, "updated_by"),
        ReferralState.valueOf(result.getString("state")),
        result.getLong("version"),
        result.getTimestamp("created_at"),
        result.getTimestamp("updated_at"));
  }

  private record DraftRow(
      InternalId id,
      UUID organizationId,
      UUID facilityId,
      String patientIssuer,
      String patientResourceType,
      String patientIdentifier,
      String encounterIssuer,
      String encounterResourceType,
      String encounterIdentifier,
      Timestamp requestedStart,
      Timestamp requestedEnd,
      UUID destinationOrganizationId,
      String priorityCode,
      ActorReference createdBy,
      ActorReference updatedBy,
      ReferralState state,
      long version,
      Timestamp createdAt,
      Timestamp updatedAt) {}

  private static RevisionRow revisionRow(ResultSet result, int row) throws SQLException {
    return new RevisionRow(
        new InternalId(result.getObject("referral_id", UUID.class)),
        result.getLong("revision_number"),
        result.getLong("referral_version"),
        result.getObject("origin_organization_id", UUID.class),
        result.getObject("origin_facility_id", UUID.class),
        result.getString("patient_issuer"),
        result.getString("patient_resource_type"),
        result.getString("patient_identifier"),
        result.getString("encounter_issuer"),
        result.getString("encounter_resource_type"),
        result.getString("encounter_identifier"),
        result.getTimestamp("requested_start"),
        result.getTimestamp("requested_end"),
        result.getObject("selected_destination_organization_id", UUID.class),
        result.getString("priority_code"),
        actor(result, "source_actor"),
        actor(result, "submitted_by"),
        result.getTimestamp("submitted_at"),
        result.getString("reason_code"));
  }

  private SubmittedReferralRevision toRevision(RevisionRow row) {
    ExternalReference patient =
        new ExternalReference(
            new IssuerId(row.patientIssuer()), row.patientResourceType(), row.patientIdentifier());
    Optional<ExternalReference> encounter =
        row.encounterIssuer() == null
            ? Optional.empty()
            : Optional.of(
                new ExternalReference(
                    new IssuerId(row.encounterIssuer()),
                    row.encounterResourceType(),
                    row.encounterIdentifier()));
    CareRequirement requirement =
        new CareRequirement(
            readRevisionCodes(
                "referral.submitted_revision_required_care_capability",
                "capability_code",
                row.id(),
                row.revisionNumber()),
            Optional.ofNullable(row.requestedStart()).map(Timestamp::toInstant),
            Optional.ofNullable(row.requestedEnd()).map(Timestamp::toInstant),
            readRevisionCodes(
                "referral.submitted_revision_permitted_geography_code",
                "geography_code",
                row.id(),
                row.revisionNumber()),
            Optional.ofNullable(row.destinationOrganizationId())
                .map(value -> directoryReference("Organization", value)),
            Optional.ofNullable(row.priorityCode()));
    return new SubmittedReferralRevision(
        row.id(),
        row.revisionNumber(),
        row.referralVersion(),
        directoryReference("Organization", row.organizationId()),
        Optional.ofNullable(row.facilityId()).map(value -> directoryReference("Facility", value)),
        new PatientReference(patient, encounter),
        requirement,
        row.sourceActor(),
        row.submittedBy(),
        row.submittedAt().toInstant(),
        Optional.ofNullable(row.reasonCode()));
  }

  private record RevisionRow(
      InternalId id,
      long revisionNumber,
      long referralVersion,
      UUID organizationId,
      UUID facilityId,
      String patientIssuer,
      String patientResourceType,
      String patientIdentifier,
      String encounterIssuer,
      String encounterResourceType,
      String encounterIdentifier,
      Timestamp requestedStart,
      Timestamp requestedEnd,
      UUID destinationOrganizationId,
      String priorityCode,
      ActorReference sourceActor,
      ActorReference submittedBy,
      Timestamp submittedAt,
      String reasonCode) {}

  private List<String> readCodes(String table, String column, InternalId id) {
    return jdbc.query(
        "SELECT " + column + " FROM " + table + " WHERE referral_id = ? ORDER BY " + column,
        (result, row) -> result.getString(column),
        id.value());
  }

  private List<String> readRevisionCodes(
      String table, String column, InternalId id, long revisionNumber) {
    return jdbc.query(
        "SELECT "
            + column
            + " FROM "
            + table
            + " WHERE referral_id = ? AND revision_number = ? ORDER BY "
            + column,
        (result, row) -> result.getString(column),
        id.value(),
        revisionNumber);
  }

  private void insertCodes(InternalId referralId, CareRequirement requirement) {
    for (String code : requirement.requiredCareCapabilityCodes()) {
      jdbc.update(
          "INSERT INTO referral.required_care_capability (referral_id, capability_code) VALUES (?, ?)",
          referralId.value(),
          code);
    }
    for (String code : requirement.permittedGeographyCodes()) {
      jdbc.update(
          "INSERT INTO referral.permitted_geography_code (referral_id, geography_code) VALUES (?, ?)",
          referralId.value(),
          code);
    }
  }

  private static ActorReference actor(ResultSet result, String prefix) throws SQLException {
    ActorKind kind = ActorKind.valueOf(result.getString(prefix + "_kind"));
    return new ActorReference(
        kind,
        new ExternalReference(
            new IssuerId(result.getString(prefix + "_issuer")),
            result.getString(prefix + "_identifier_type"),
            result.getString(prefix + "_identifier")));
  }

  private static UUID directoryId(ExternalReference reference) {
    if (!DIRECTORY_ISSUER.equals(reference.issuer().value())) {
      throw new IllegalArgumentException("Referral Directory reference has an unexpected issuer");
    }
    return UUID.fromString(reference.value());
  }

  private static ExternalReference directoryReference(String type, UUID value) {
    return new ExternalReference(new IssuerId(DIRECTORY_ISSUER), type, value.toString());
  }
}
