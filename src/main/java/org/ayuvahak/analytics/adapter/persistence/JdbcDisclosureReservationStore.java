package org.ayuvahak.analytics.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.ayuvahak.analytics.application.DisclosureReleaseContext;
import org.ayuvahak.analytics.application.DisclosureReviewContext;
import org.ayuvahak.analytics.application.DisclosureReservationException;
import org.ayuvahak.analytics.application.DisclosureReservationStore;
import org.ayuvahak.analytics.domain.DisclosureCandidate;
import org.ayuvahak.analytics.domain.DisclosureOutput;
import org.ayuvahak.analytics.domain.DisclosurePolicyActivation;
import org.ayuvahak.analytics.domain.DisclosurePolicyVersion;
import org.ayuvahak.analytics.domain.DisclosureReservation;
import org.ayuvahak.analytics.domain.DisclosureReservationState;
import org.ayuvahak.analytics.domain.DisclosureScope;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** PostgreSQL locking and persistence adapter for release-set disclosure history. */
@Repository
public class JdbcDisclosureReservationStore implements DisclosureReservationStore {

  private static final int MAX_LINEAGES_PER_POLICY = 4096;
  private static final int MAX_HISTORY_OUTPUTS = 256;
  private static final Set<DisclosureReservationState> ACTIVE_HISTORY_STATES =
      Set.of(
          DisclosureReservationState.RESERVED,
          DisclosureReservationState.POTENTIALLY_DISCLOSED,
          DisclosureReservationState.DISCLOSED,
          // A later related release must remain safe if this pending review is approved.
          DisclosureReservationState.REVIEW_REQUIRED);

  private static final RowMapper<DisclosureReservation> RESERVATION_MAPPER =
      (result, rowNumber) -> reservation(result, false, Set.of());

  private final JdbcTemplate jdbc;

  public JdbcDisclosureReservationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<DisclosurePolicyVersion> activePolicy(String policyKey) {
    Optional<PolicyHead> head = findHead(policyKey, false);
    return head.map(this::loadPolicyVersion);
  }

  @Override
  public DisclosurePolicyActivation activatePolicy(
      DisclosurePolicyVersion proposed, Instant activatedAt) {
    ensureLineageRows(
        proposed.organization(), Set.of(proposed.conservativeLineageId()), activatedAt);
    ensureOrganizationPrivacyScope(proposed, activatedAt);
    jdbc.update(
        "INSERT INTO analytics.disclosure_policy_head (policy_key, organization_issuer, "
            + "organization_reference_type, organization_id, conservative_lineage_id, active_version, "
            + "history_checkpoint, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, 1, 0, ?, ?) ON CONFLICT (policy_key) DO NOTHING",
        proposed.policyKey(),
        proposed.organization().issuer().value(),
        proposed.organization().resourceType(),
        UUID.fromString(proposed.organization().value()),
        proposed.conservativeLineageId(),
        Timestamp.from(activatedAt),
        Timestamp.from(activatedAt));

    PolicyHead head =
        findHead(proposed.policyKey(), true)
            .orElseThrow(
                () ->
                    new DisclosureReservationException(
                        DisclosureReservationException.Reason.POLICY_NOT_FOUND));
    validatePolicyOwner(head, proposed);

    Optional<PolicyVersionHeader> existingVersion =
        findPolicyVersion(proposed.policyKey(), proposed.version());
    if (existingVersion.isPresent()) {
      if (!existingVersion.orElseThrow().requestedFingerprint().equals(proposed.fingerprint())) {
        throw new DisclosureReservationException(
            DisclosureReservationException.Reason.POLICY_VERSION_CONFLICT);
      }
      return new DisclosurePolicyActivation(loadPolicyVersion(head, proposed.version()), true);
    }
    int expectedVersion =
        head.activeVersion() == 1 && !policyVersionExists(proposed.policyKey(), 1)
            ? 1
            : head.activeVersion() + 1;
    if (proposed.version() != expectedVersion) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.POLICY_VERSION_CONFLICT);
    }

    Map<String, Set<UUID>> priorLineages =
        head.activeVersion() == 1 && !policyVersionExists(proposed.policyKey(), 1)
            ? Map.of()
            : loadLineageMap(proposed.policyKey(), head.activeVersion());
    Map<String, Set<UUID>> effectiveLineages = broadenMappings(proposed, priorLineages);
    Set<UUID> allLineages =
        effectiveLineages.values().stream()
            .flatMap(Set::stream)
            .collect(Collectors.toCollection(TreeSet::new));
    if (allLineages.size() > MAX_LINEAGES_PER_POLICY) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.LINEAGE_INVALID);
    }
    ensureLineageRows(proposed.organization(), allLineages, activatedAt);
    lockLineages(allLineages);

    DisclosurePolicyVersion effective =
        new DisclosurePolicyVersion(
            proposed.policyKey(),
            proposed.version(),
            proposed.organization(),
            proposed.minimumCellCount(),
            proposed.externalBaselineComplete(),
            proposed.conservativeLineageId(),
            effectiveLineages);
    jdbc.update(
        "INSERT INTO analytics.disclosure_policy_version (policy_key, policy_version, "
            + "minimum_cell_count, external_baseline_complete, conservative_lineage_id, "
            + "requested_fingerprint, effective_fingerprint, activated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        effective.policyKey(),
        effective.version(),
        effective.minimumCellCount(),
        effective.externalBaselineComplete(),
        effective.conservativeLineageId(),
        proposed.fingerprint(),
        effective.fingerprint(),
        Timestamp.from(activatedAt));
    insertLineageMap(effective);
    jdbc.update(
        "UPDATE analytics.disclosure_policy_head SET active_version = ?, "
            + "history_checkpoint = history_checkpoint + 1, updated_at = ? WHERE policy_key = ?",
        effective.version(),
        Timestamp.from(activatedAt),
        effective.policyKey());
    return new DisclosurePolicyActivation(effective, false);
  }

  @Override
  public DisclosureReleaseContext lockForRelease(DisclosureCandidate candidate) {
    Optional<DisclosureReservation> prior =
        findByOperation(candidate.policyKey(), candidate.operationId());
    if (prior.isPresent()) {
      return replayContext(candidate, prior.orElseThrow());
    }

    PolicyHead currentHead =
        findHead(candidate.policyKey(), true)
            .orElseThrow(
                () ->
                    new DisclosureReservationException(
                        DisclosureReservationException.Reason.POLICY_NOT_FOUND));
    DisclosurePolicyVersion policy = loadPolicyVersion(currentHead);
    if (policy.version() != candidate.expectedPolicyVersion()) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.POLICY_VERSION_CONFLICT);
    }
    Set<UUID> lineageIds = candidateLineages(policy, candidate);
    lockLineages(lineageIds);
    Optional<DisclosureReservation> concurrent =
        findByOperation(candidate.policyKey(), candidate.operationId());
    if (concurrent.isPresent()) {
      return replayContext(candidate, concurrent.orElseThrow());
    }
    boolean scopeHistoryCompatible = !hasOverlappingScopeHistory(candidate, lineageIds);
    History history;
    try {
      history = loadHistory(lineageIds);
    } catch (InconsistentHistoryException inconsistentHistory) {
      history = new History(List.of(), Map.of(), false, List.of());
    }
    return new DisclosureReleaseContext(
        policy,
        lineageIds,
        history.outputs(),
        history.cellValues(),
        history.complete(),
        currentHead.historyCheckpoint(),
        Optional.empty(),
        scopeHistoryCompatible,
        history.potentiallyDisclosedOutputs());
  }

  @Override
  public DisclosureReservation recordDecision(
      DisclosureCandidate candidate,
      DisclosureReleaseContext lockedHistory,
      DisclosureReservationState state,
      String reasonCode,
      Instant recordedAt) {
    if (!(state == DisclosureReservationState.RESERVED
        || state == DisclosureReservationState.WITHHELD
        || state == DisclosureReservationState.REVIEW_REQUIRED)) {
      throw new IllegalArgumentException("a release decision state is required");
    }
    validateLockedContext(candidate, lockedHistory);
    Optional<DisclosureReservation> prior =
        findByOperation(candidate.policyKey(), candidate.operationId());
    if (prior.isPresent()) {
      return replay(prior.orElseThrow(), candidate.fingerprint());
    }
    long checkpoint = bumpCheckpoint(candidate.policyKey(), recordedAt);
    UUID reservationId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO analytics.disclosure_reservation (reservation_id, policy_key, policy_version, "
            + "operation_id, operation_fingerprint, state, reason_code, origin, history_checkpoint, "
                + "scope_indicator_key, scope_indicator_version, scope_organization_issuer, "
                + "scope_organization_id, scope_facility_id, "
            + "scope_period_start_utc, scope_period_end_exclusive_utc, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 'LOCAL_RELEASE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        reservationId,
        candidate.policyKey(),
        lockedHistory.policy().version(),
        candidate.operationId(),
        candidate.fingerprint(),
        state.name(),
        reasonCode,
        checkpoint,
        candidate.scope().map(DisclosureScope::indicatorKey).orElse(null),
        candidate.scope().map(DisclosureScope::indicatorVersion).orElse(null),
        candidate.scope().map(DisclosureScope::organizationIssuer).orElse(null),
        candidate.scope().map(DisclosureScope::organizationId).orElse(null),
        candidate.scope().flatMap(DisclosureScope::facilityId).orElse(null),
        candidate.scope().map(scope -> Timestamp.from(scope.periodStartInclusive())).orElse(null),
        candidate.scope().map(scope -> Timestamp.from(scope.periodEndExclusive())).orElse(null),
        Timestamp.from(recordedAt),
        Timestamp.from(recordedAt));
    insertReservationLineages(reservationId, lockedHistory.lineageIds());
    if (state == DisclosureReservationState.RESERVED
        || state == DisclosureReservationState.REVIEW_REQUIRED) {
      insertOutputs(reservationId, candidate.outputs(), candidate.cellValues());
    }
    return new DisclosureReservation(
        reservationId,
        candidate.policyKey(),
        lockedHistory.policy().version(),
        candidate.operationId(),
        candidate.fingerprint(),
        lockedHistory.lineageIds(),
        checkpoint,
        state,
        reasonCode,
        recordedAt,
        recordedAt,
        false);
  }

  @Override
  public DisclosureReservation recordKnownExternalBaseline(
      DisclosureCandidate candidate, DisclosureReleaseContext lockedHistory, Instant recordedAt) {
    validateLockedContext(candidate, lockedHistory);
    Optional<DisclosureReservation> prior =
        findByOperation(candidate.policyKey(), candidate.operationId());
    if (prior.isPresent()) {
      return replay(prior.orElseThrow(), candidate.fingerprint());
    }
    long checkpoint = bumpCheckpoint(candidate.policyKey(), recordedAt);
    UUID reservationId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO analytics.disclosure_reservation (reservation_id, policy_key, policy_version, "
            + "operation_id, operation_fingerprint, state, reason_code, origin, history_checkpoint, "
                + "scope_indicator_key, scope_indicator_version, scope_organization_issuer, "
                + "scope_organization_id, scope_facility_id, "
            + "scope_period_start_utc, scope_period_end_exclusive_utc, "
            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'DISCLOSED', 'KNOWN_EXTERNAL_BASELINE', "
                + "'KNOWN_EXTERNAL_BASELINE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        reservationId,
        candidate.policyKey(),
        lockedHistory.policy().version(),
        candidate.operationId(),
        candidate.fingerprint(),
        checkpoint,
        candidate.scope().map(DisclosureScope::indicatorKey).orElse(null),
        candidate.scope().map(DisclosureScope::indicatorVersion).orElse(null),
        candidate.scope().map(DisclosureScope::organizationIssuer).orElse(null),
        candidate.scope().map(DisclosureScope::organizationId).orElse(null),
        candidate.scope().flatMap(DisclosureScope::facilityId).orElse(null),
        candidate.scope().map(scope -> Timestamp.from(scope.periodStartInclusive())).orElse(null),
        candidate.scope().map(scope -> Timestamp.from(scope.periodEndExclusive())).orElse(null),
        Timestamp.from(recordedAt),
        Timestamp.from(recordedAt));
    insertReservationLineages(reservationId, lockedHistory.lineageIds());
    insertOutputs(reservationId, candidate.outputs(), candidate.cellValues());
    return new DisclosureReservation(
        reservationId,
        candidate.policyKey(),
        lockedHistory.policy().version(),
        candidate.operationId(),
        candidate.fingerprint(),
        lockedHistory.lineageIds(),
        checkpoint,
        DisclosureReservationState.DISCLOSED,
        "KNOWN_EXTERNAL_BASELINE",
        recordedAt,
        recordedAt,
        false);
  }

  @Override
  public Optional<DisclosureReservation> findReservation(UUID reservationId) {
    List<DisclosureReservation> rows =
        jdbc.query(
            "SELECT reservation_id, policy_key, policy_version, operation_id, operation_fingerprint, "
                + "history_checkpoint, state, reason_code, created_at, updated_at "
                + "FROM analytics.disclosure_reservation WHERE reservation_id = ?",
            RESERVATION_MAPPER,
            reservationId);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    DisclosureReservation row = rows.getFirst();
    return Optional.of(withLineages(row, loadReservationLineages(reservationId), false));
  }

  @Override
  public Optional<ExternalReference> organizationForReservation(UUID reservationId) {
    return jdbc
        .query(
            "SELECT head.organization_issuer, head.organization_reference_type, head.organization_id "
                + "FROM analytics.disclosure_reservation reservation "
                + "JOIN analytics.disclosure_policy_head head ON head.policy_key = reservation.policy_key "
                + "WHERE reservation.reservation_id = ?",
            (result, row) ->
                new ExternalReference(
                    new IssuerId(result.getString("organization_issuer")),
                    result.getString("organization_reference_type"),
                    result.getObject("organization_id", UUID.class).toString()),
            reservationId)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<DisclosureReviewContext> lockCandidateForReview(
      UUID reservationId, String expectedOperationFingerprint) {
    DisclosureReservation existing =
        findReservation(reservationId)
            .orElseThrow(
                () ->
                    new DisclosureReservationException(
                        DisclosureReservationException.Reason.RESERVATION_NOT_FOUND));
    PolicyHead head =
        findHead(existing.policyKey(), true)
            .orElseThrow(
                () ->
                    new DisclosureReservationException(
                        DisclosureReservationException.Reason.POLICY_NOT_FOUND));
    if (head.activeVersion() != existing.policyVersion()) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.POLICY_VERSION_CONFLICT);
    }
    lockLineages(existing.lineageIds());
    DisclosureReservation locked =
        findReservationForUpdate(reservationId)
            .orElseThrow(
                () ->
                    new DisclosureReservationException(
                        DisclosureReservationException.Reason.RESERVATION_NOT_FOUND));
    if (!locked.operationFingerprint().equals(expectedOperationFingerprint)) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.OPERATION_ID_REUSED);
    }
    if (locked.state() != DisclosureReservationState.REVIEW_REQUIRED) {
      return Optional.empty();
    }

    List<StoredReviewOutput> storedOutputs =
        jdbc.query(
            "SELECT output_key, output_value, equation_result, applies_minimum_cell_count "
                + "FROM analytics.disclosure_reservation_output WHERE reservation_id = ? "
                + "ORDER BY output_key",
            (result, row) ->
                new StoredReviewOutput(
                    result.getString("output_key"),
                    result.getLong("output_value"),
                    result.getLong("equation_result"),
                    result.getBoolean("applies_minimum_cell_count")),
            reservationId);
    if (storedOutputs.isEmpty()) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.LINEAGE_INVALID);
    }
    Map<String, Map<String, Long>> termsByOutput = new HashMap<>();
    jdbc.query(
        "SELECT output_key, cell_key, coefficient FROM analytics.disclosure_reservation_term "
            + "WHERE reservation_id = ? ORDER BY output_key, cell_key",
        (RowCallbackHandler)
            result ->
                termsByOutput
                    .computeIfAbsent(result.getString("output_key"), ignored -> new TreeMap<>())
                    .put(result.getString("cell_key"), result.getLong("coefficient")),
        reservationId);
    List<DisclosureOutput> outputs =
        storedOutputs.stream()
            .map(
                output -> {
                  Map<String, Long> terms = termsByOutput.get(output.outputKey());
                  if (terms == null || terms.isEmpty()) {
                    throw new DisclosureReservationException(
                        DisclosureReservationException.Reason.LINEAGE_INVALID);
                  }
                  return new DisclosureOutput(
                      output.outputKey(),
                      output.value(),
                      terms,
                      output.equationResult(),
                      output.appliesMinimumCellCount());
                })
            .toList();
    Map<String, Long> cells = new TreeMap<>();
    jdbc.query(
        "SELECT cell_key, cell_value FROM analytics.disclosure_reservation_cell "
            + "WHERE reservation_id = ? ORDER BY cell_key",
        (RowCallbackHandler)
            result -> cells.put(result.getString("cell_key"), result.getLong("cell_value")),
        reservationId);
    try {
      DisclosureCandidate candidate =
          new DisclosureCandidate(
              locked.policyKey(), locked.policyVersion(), locked.operationId(), cells, outputs);
      History history;
      try {
        history = loadHistory(locked.lineageIds());
      } catch (InconsistentHistoryException inconsistentHistory) {
        history = new History(List.of(), Map.of(), false, List.of());
      }
      return Optional.of(
          new DisclosureReviewContext(candidate, history.potentiallyDisclosedOutputs()));
    } catch (IllegalArgumentException malformedEvidence) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.LINEAGE_INVALID, malformedEvidence);
    }
  }

  @Override
  public DisclosureReservation transition(
      UUID reservationId,
      DisclosureReservationState targetState,
      String reasonCode,
      Instant changedAt) {
    DisclosureReservation existing =
        findReservation(reservationId)
            .orElseThrow(
                () ->
                    new DisclosureReservationException(
                        DisclosureReservationException.Reason.RESERVATION_NOT_FOUND));
    findHead(existing.policyKey(), true)
        .orElseThrow(
            () ->
                new DisclosureReservationException(
                    DisclosureReservationException.Reason.POLICY_NOT_FOUND));
    lockLineages(existing.lineageIds());
    DisclosureReservation locked =
        findReservationForUpdate(reservationId)
            .orElseThrow(
                () ->
                    new DisclosureReservationException(
                        DisclosureReservationException.Reason.RESERVATION_NOT_FOUND));
    if (locked.state() == targetState) {
      return withLineages(locked, locked.lineageIds(), true);
    }
    if (!allowedTransition(locked.state(), targetState)) {
      throw new DisclosureReservationException(DisclosureReservationException.Reason.INVALID_STATE);
    }
    long checkpoint = bumpCheckpoint(locked.policyKey(), changedAt);
    jdbc.update(
        "UPDATE analytics.disclosure_reservation SET state = ?, reason_code = ?, "
            + "history_checkpoint = ?, updated_at = ? WHERE reservation_id = ?",
        targetState.name(),
        reasonCode,
        checkpoint,
        Timestamp.from(changedAt),
        reservationId);
    return new DisclosureReservation(
        locked.reservationId(),
        locked.policyKey(),
        locked.policyVersion(),
        locked.operationId(),
        locked.operationFingerprint(),
        locked.lineageIds(),
        checkpoint,
        targetState,
        reasonCode,
        locked.createdAt(),
        changedAt,
        false);
  }

  @Override
  public DisclosureReservation resolveReviewRequired(
      UUID reservationId,
      String expectedOperationFingerprint,
      DisclosureReservationState targetState,
      UUID evidenceReference,
      org.ayuvahak.platform.core.ActorReference reviewer,
      UUID auditRecordId,
      Instant resolvedAt) {
    if (targetState != DisclosureReservationState.RESERVED
        && targetState != DisclosureReservationState.WITHHELD) {
      throw new IllegalArgumentException("review resolution state is invalid");
    }
    Objects.requireNonNull(evidenceReference, "review evidence reference");
    Objects.requireNonNull(reviewer, "reviewer");
    Objects.requireNonNull(auditRecordId, "review audit record id");
    Objects.requireNonNull(resolvedAt, "review time");

    DisclosureReservation existing =
        findReservation(reservationId)
            .orElseThrow(
                () ->
                    new DisclosureReservationException(
                        DisclosureReservationException.Reason.RESERVATION_NOT_FOUND));
    PolicyHead head =
        findHead(existing.policyKey(), true)
            .orElseThrow(
                () ->
                    new DisclosureReservationException(
                        DisclosureReservationException.Reason.POLICY_NOT_FOUND));
    if (head.activeVersion() != existing.policyVersion()) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.POLICY_VERSION_CONFLICT);
    }
    lockLineages(existing.lineageIds());
    DisclosureReservation locked =
        findReservationForUpdate(reservationId)
            .orElseThrow(
                () ->
                    new DisclosureReservationException(
                        DisclosureReservationException.Reason.RESERVATION_NOT_FOUND));
    if (!locked.operationFingerprint().equals(expectedOperationFingerprint)) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.OPERATION_ID_REUSED);
    }
    Optional<ReviewResolution> prior = findReviewResolution(reservationId);
    if (prior.isPresent()) {
      ReviewResolution recorded = prior.orElseThrow();
      if (locked.state() == targetState
          && recorded.state() == targetState
          && recorded.evidenceReference().equals(evidenceReference)) {
        return withLineages(locked, locked.lineageIds(), true);
      }
      throw new DisclosureReservationException(DisclosureReservationException.Reason.INVALID_STATE);
    }
    if (locked.state() != DisclosureReservationState.REVIEW_REQUIRED) {
      throw new DisclosureReservationException(DisclosureReservationException.Reason.INVALID_STATE);
    }
    long checkpoint = bumpCheckpoint(locked.policyKey(), resolvedAt);
    String reason =
        targetState == DisclosureReservationState.RESERVED
            ? "AUTHORIZED_REVIEW_APPROVED"
            : "AUTHORIZED_REVIEW_WITHHELD";
    jdbc.update(
        "UPDATE analytics.disclosure_reservation SET state = ?, reason_code = ?, "
            + "history_checkpoint = ?, updated_at = ? WHERE reservation_id = ?",
        targetState.name(),
        reason,
        checkpoint,
        Timestamp.from(resolvedAt),
        reservationId);
    jdbc.update(
        "INSERT INTO analytics.disclosure_reservation_review (reservation_id, decision, "
            + "evidence_reference, reviewer_issuer, reviewer_resource_type, reviewer_identity, "
            + "audit_record_id, resolved_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        reservationId,
        targetState == DisclosureReservationState.RESERVED ? "APPROVED" : "WITHHELD",
        evidenceReference,
        reviewer.identity().issuer().value(),
        reviewer.identity().resourceType(),
        reviewer.identity().value(),
        auditRecordId,
        Timestamp.from(resolvedAt));
    return new DisclosureReservation(
        locked.reservationId(),
        locked.policyKey(),
        locked.policyVersion(),
        locked.operationId(),
        locked.operationFingerprint(),
        locked.lineageIds(),
        checkpoint,
        targetState,
        reason,
        locked.createdAt(),
        resolvedAt,
        false);
  }

  private Optional<ReviewResolution> findReviewResolution(UUID reservationId) {
    return jdbc
        .query(
            "SELECT decision, evidence_reference FROM analytics.disclosure_reservation_review "
                + "WHERE reservation_id = ?",
            (result, row) ->
                new ReviewResolution(
                    DisclosureReservationState.valueOf(
                        result.getString("decision").equals("APPROVED") ? "RESERVED" : "WITHHELD"),
                    result.getObject("evidence_reference", UUID.class)),
            reservationId)
        .stream()
        .findFirst();
  }

  @Override
  public void attachPolicyAudit(String policyKey, int policyVersion, UUID auditRecordId) {
    int changed =
        jdbc.update(
            "UPDATE analytics.disclosure_policy_version SET audit_record_id = ? "
                + "WHERE policy_key = ? AND policy_version = ? AND audit_record_id IS NULL",
            auditRecordId,
            policyKey,
            policyVersion);
    if (changed != 1) {
      throw new IllegalStateException("disclosure policy audit could not be attached");
    }
  }

  @Override
  public void attachReservationAudit(UUID reservationId, UUID auditRecordId) {
    int changed =
        jdbc.update(
            "UPDATE analytics.disclosure_reservation SET audit_record_id = ? "
                + "WHERE reservation_id = ? AND audit_record_id IS NULL",
            auditRecordId,
            reservationId);
    if (changed != 1) {
      throw new IllegalStateException("disclosure reservation audit could not be attached");
    }
  }

  private Optional<PolicyHead> findHead(String policyKey, boolean lock) {
    String lockSuffix = lock ? " FOR UPDATE" : "";
    List<PolicyHead> rows =
        jdbc.query(
            "SELECT policy_key, organization_issuer, organization_reference_type, organization_id, "
                + "conservative_lineage_id, active_version, history_checkpoint "
                + "FROM analytics.disclosure_policy_head WHERE policy_key = ?"
                + lockSuffix,
            (result, rowNumber) ->
                new PolicyHead(
                    result.getString("policy_key"),
                    result.getString("organization_issuer"),
                    result.getString("organization_reference_type"),
                    result.getObject("organization_id", UUID.class),
                    result.getObject("conservative_lineage_id", UUID.class),
                    result.getInt("active_version"),
                    result.getLong("history_checkpoint")),
            policyKey);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  private DisclosurePolicyVersion loadPolicyVersion(PolicyHead head) {
    return loadPolicyVersion(head, head.activeVersion());
  }

  private DisclosurePolicyVersion loadPolicyVersion(PolicyHead head, int version) {
    PolicyVersionHeader policy =
        findPolicyVersion(head.policyKey(), version)
            .orElseThrow(
                () ->
                    new DisclosureReservationException(
                        DisclosureReservationException.Reason.POLICY_NOT_FOUND));
    Map<String, Set<UUID>> lineages = loadLineageMap(head.policyKey(), version);
    return new DisclosurePolicyVersion(
        head.policyKey(),
        version,
        new ExternalReference(
            new IssuerId(head.organizationIssuer()),
            head.organizationReferenceType(),
            head.organizationId().toString()),
        policy.minimumCellCount(),
        policy.externalBaselineComplete(),
        head.conservativeLineageId(),
        lineages);
  }

  private Optional<PolicyVersionHeader> findPolicyVersion(String policyKey, int version) {
    List<PolicyVersionHeader> rows =
        jdbc.query(
            "SELECT minimum_cell_count, external_baseline_complete, requested_fingerprint "
                + "FROM analytics.disclosure_policy_version WHERE policy_key = ? AND policy_version = ?",
            (result, rowNumber) ->
                new PolicyVersionHeader(
                    result.getLong("minimum_cell_count"),
                    result.getBoolean("external_baseline_complete"),
                    result.getString("requested_fingerprint")),
            policyKey,
            version);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  private boolean policyVersionExists(String policyKey, int version) {
    Boolean exists =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM analytics.disclosure_policy_version "
                + "WHERE policy_key = ? AND policy_version = ?)",
            Boolean.class,
            policyKey,
            version);
    return Boolean.TRUE.equals(exists);
  }

  private Map<String, Set<UUID>> loadLineageMap(String policyKey, int version) {
    Map<String, Set<UUID>> assignments = new TreeMap<>();
    jdbc.query(
        "SELECT output_key, lineage_id FROM analytics.disclosure_policy_output_lineage "
            + "WHERE policy_key = ? AND policy_version = ? ORDER BY output_key, lineage_id",
        (org.springframework.jdbc.core.RowCallbackHandler)
            result ->
                assignments
                    .computeIfAbsent(result.getString("output_key"), ignored -> new TreeSet<>())
                    .add(result.getObject("lineage_id", UUID.class)),
        policyKey,
        version);
    return assignments.entrySet().stream()
        .collect(
            Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
  }

  private Map<String, Set<UUID>> broadenMappings(
      DisclosurePolicyVersion proposed, Map<String, Set<UUID>> priorLineages) {
    Map<String, Set<UUID>> effective = new TreeMap<>();
    proposed
        .outputLineages()
        .forEach(
            (outputKey, newLineages) -> {
              Set<UUID> union = new TreeSet<>(newLineages);
              union.add(proposed.conservativeLineageId());
              union.addAll(priorLineages.getOrDefault(outputKey, Set.of()));
              effective.put(outputKey, Set.copyOf(union));
            });
    if (effective.isEmpty()) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.LINEAGE_INVALID);
    }
    return Map.copyOf(effective);
  }

  private void insertLineageMap(DisclosurePolicyVersion policy) {
    policy.outputLineages().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry ->
                entry.getValue().stream()
                    .sorted()
                    .forEach(
                        lineage ->
                            jdbc.update(
                                "INSERT INTO analytics.disclosure_policy_output_lineage "
                                    + "(policy_key, policy_version, output_key, lineage_id) VALUES (?, ?, ?, ?)",
                                policy.policyKey(),
                                policy.version(),
                                entry.getKey(),
                                lineage)));
  }

  private Set<UUID> candidateLineages(
      DisclosurePolicyVersion policy, DisclosureCandidate candidate) {
    Set<UUID> lineages = new TreeSet<>();
    for (DisclosureOutput output : candidate.outputs()) {
      Set<UUID> mapped = policy.lineagesFor(output.outputKey());
      if (mapped.isEmpty() || !mapped.contains(policy.conservativeLineageId())) {
        throw new DisclosureReservationException(
            DisclosureReservationException.Reason.LINEAGE_INVALID);
      }
      lineages.addAll(mapped);
    }
    if (lineages.isEmpty()) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.LINEAGE_INVALID);
    }
    return Set.copyOf(lineages);
  }

  private void ensureOrganizationPrivacyScope(DisclosurePolicyVersion proposed, Instant createdAt) {
    UUID organizationId = UUID.fromString(proposed.organization().value());
    jdbc.update(
        "INSERT INTO analytics.disclosure_privacy_scope (organization_issuer, "
            + "organization_reference_type, organization_id, conservative_lineage_id, created_at) "
            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (organization_issuer, "
            + "organization_reference_type, organization_id) DO NOTHING",
        proposed.organization().issuer().value(),
        proposed.organization().resourceType(),
        organizationId,
        proposed.conservativeLineageId(),
        Timestamp.from(createdAt));
    UUID configuredLineage =
        jdbc.queryForObject(
            "SELECT conservative_lineage_id FROM analytics.disclosure_privacy_scope "
                + "WHERE organization_issuer = ? AND organization_reference_type = ? "
                + "AND organization_id = ? FOR UPDATE",
            UUID.class,
            proposed.organization().issuer().value(),
            proposed.organization().resourceType(),
            organizationId);
    if (!proposed.conservativeLineageId().equals(configuredLineage)) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.LINEAGE_INVALID);
    }
  }

  private void ensureLineageRows(
      ExternalReference organization, Set<UUID> lineageIds, Instant createdAt) {
    UUID organizationId = UUID.fromString(organization.value());
    for (UUID lineageId : lineageIds.stream().sorted().toList()) {
      jdbc.update(
          "INSERT INTO analytics.disclosure_lineage (lineage_id, organization_issuer, "
              + "organization_reference_type, organization_id, created_at) "
              + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (lineage_id) DO NOTHING",
          lineageId,
          organization.issuer().value(),
          organization.resourceType(),
          organizationId,
          Timestamp.from(createdAt));
      List<UUID> existingOwner =
          jdbc.query(
              "SELECT organization_id FROM analytics.disclosure_lineage "
                  + "WHERE lineage_id = ? AND organization_issuer = ? "
                  + "AND organization_reference_type = ?",
              (result, row) -> result.getObject("organization_id", UUID.class),
              lineageId,
              organization.issuer().value(),
              organization.resourceType());
      if (existingOwner.size() != 1 || !organizationId.equals(existingOwner.getFirst())) {
        throw new DisclosureReservationException(
            DisclosureReservationException.Reason.LINEAGE_INVALID);
      }
    }
  }

  /** Mutation lock order is policy head, sorted disclosure lineages, then reservation rows. */
  private void lockLineages(Set<UUID> requestedLineages) {
    List<UUID> lineages = requestedLineages.stream().sorted().toList();
    if (lineages.isEmpty() || lineages.size() > MAX_LINEAGES_PER_POLICY) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.LINEAGE_INVALID);
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(lineages.size(), "?"));
    List<UUID> locked =
        jdbc.query(
            "SELECT lineage_id FROM analytics.disclosure_lineage WHERE lineage_id IN ("
                + placeholders
                + ") ORDER BY lineage_id FOR UPDATE",
            (result, rowNumber) -> result.getObject("lineage_id", UUID.class),
            lineages.toArray());
    if (locked.size() != lineages.size()) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.LINEAGE_INVALID);
    }
  }

  private History loadHistory(Set<UUID> lineages) {
    String placeholders = String.join(",", java.util.Collections.nCopies(lineages.size(), "?"));
    List<Object> values = new ArrayList<>();
    String statePlaceholders =
        String.join(",", java.util.Collections.nCopies(ACTIVE_HISTORY_STATES.size(), "?"));
    ACTIVE_HISTORY_STATES.stream().map(Enum::name).sorted().forEach(values::add);
    values.addAll(lineages.stream().sorted().toList());
    Object[] queryValues = values.toArray();
    List<StoredOutput> outputs =
        jdbc.query(
            "SELECT DISTINCT r.reservation_id, r.state, o.output_key, o.output_value, "
                + "o.equation_result, o.applies_minimum_cell_count "
                + "FROM analytics.disclosure_reservation r "
                + "JOIN analytics.disclosure_reservation_output o ON o.reservation_id = r.reservation_id "
                + "WHERE r.state IN ("
                + statePlaceholders
                + ") AND EXISTS (SELECT 1 FROM analytics.disclosure_reservation_lineage rl "
                + "WHERE rl.reservation_id = r.reservation_id AND rl.lineage_id IN ("
                + placeholders
                + ")) ORDER BY r.reservation_id, o.output_key LIMIT "
                + (MAX_HISTORY_OUTPUTS + 1),
            (result, rowNumber) ->
                new StoredOutput(
                    result.getObject("reservation_id", UUID.class),
                    result.getString("output_key"),
                    result.getLong("output_value"),
                    result.getLong("equation_result"),
                    result.getBoolean("applies_minimum_cell_count"),
                    DisclosureReservationState.valueOf(result.getString("state"))),
            queryValues);
    boolean complete = outputs.size() <= MAX_HISTORY_OUTPUTS;
    if (!complete) {
      outputs = outputs.subList(0, MAX_HISTORY_OUTPUTS);
    }
    Map<UUID, Set<String>> outputsByReservation = new HashMap<>();
    for (StoredOutput output : outputs) {
      outputsByReservation
          .computeIfAbsent(output.reservationId(), ignored -> new HashSet<>())
          .add(output.outputKey());
    }
    List<Object> termValues = new ArrayList<>();
    List<Object> cellValues = new ArrayList<>();
    outputsByReservation.keySet().stream().sorted().forEach(termValues::add);
    String reservationPlaceholders =
        String.join(",", java.util.Collections.nCopies(outputsByReservation.size(), "?"));
    Map<OutputIdentity, Map<String, Long>> terms = new HashMap<>();
    if (!outputsByReservation.isEmpty()) {
      jdbc.query(
          "SELECT reservation_id, output_key, cell_key, coefficient "
              + "FROM analytics.disclosure_reservation_term WHERE reservation_id IN ("
              + reservationPlaceholders
              + ") ORDER BY reservation_id, output_key, cell_key",
          result -> {
            OutputIdentity identity =
                new OutputIdentity(
                    result.getObject("reservation_id", UUID.class), result.getString("output_key"));
            if (outputsByReservation
                .getOrDefault(identity.reservationId(), Set.of())
                .contains(identity.outputKey())) {
              terms
                  .computeIfAbsent(identity, ignored -> new TreeMap<>())
                  .put(result.getString("cell_key"), result.getLong("coefficient"));
            }
          },
          termValues.toArray());
      outputsByReservation.keySet().stream().sorted().forEach(cellValues::add);
    }
    Map<String, Long> historicalCells = new HashMap<>();
    if (!outputsByReservation.isEmpty()) {
      jdbc.query(
          "SELECT DISTINCT c.cell_key, c.cell_value FROM analytics.disclosure_reservation_cell c "
              + "WHERE c.reservation_id IN ("
              + reservationPlaceholders
              + ") ORDER BY c.cell_key",
          result -> {
            long value = result.getLong("cell_value");
            Long prior = historicalCells.putIfAbsent(result.getString("cell_key"), value);
            if (prior != null && prior != value) {
              throw new InconsistentHistoryException();
            }
          },
          cellValues.toArray());
    }
    List<DisclosureOutput> restored = new ArrayList<>();
    List<DisclosureOutput> potentiallyDisclosed = new ArrayList<>();
    for (StoredOutput output : outputs) {
      Map<String, Long> outputTerms =
          terms.get(new OutputIdentity(output.reservationId(), output.outputKey()));
      if (outputTerms == null || outputTerms.isEmpty()) {
        complete = false;
        continue;
      }
      try {
        DisclosureOutput disclosureOutput =
            new DisclosureOutput(
                output.outputKey(),
                output.value(),
                outputTerms,
                output.equationResult(),
                output.appliesMinimumCellCount());
        restored.add(disclosureOutput);
        if (output.state() != DisclosureReservationState.REVIEW_REQUIRED) {
          potentiallyDisclosed.add(disclosureOutput);
        }
      } catch (IllegalArgumentException malformedHistory) {
        complete = false;
      }
    }
    return new History(
        List.copyOf(restored),
        Map.copyOf(historicalCells),
        complete,
        List.copyOf(potentiallyDisclosed));
  }

  private boolean hasOverlappingScopeHistory(DisclosureCandidate candidate, Set<UUID> lineages) {
    Optional<DisclosureScope> candidateScope = candidate.scope();
    if (candidateScope.isEmpty()) {
      return false;
    }
    DisclosureScope scope = candidateScope.orElseThrow();
    String lineagePlaceholders =
        String.join(",", java.util.Collections.nCopies(lineages.size(), "?"));
    String statePlaceholders =
        String.join(",", java.util.Collections.nCopies(ACTIVE_HISTORY_STATES.size(), "?"));
    List<Object> values = new ArrayList<>();
    ACTIVE_HISTORY_STATES.stream().map(Enum::name).sorted().forEach(values::add);
    values.add(candidate.operationId());
    values.addAll(lineages.stream().sorted().toList());
    values.add(scope.organizationIssuer());
    values.add(scope.organizationId());
    values.add(Timestamp.from(scope.periodEndExclusive()));
    values.add(Timestamp.from(scope.periodStartInclusive()));
    values.add(scope.indicatorKey());
    values.add(scope.indicatorVersion());
    values.add(scope.facilityId().orElse(null));
    values.add(Timestamp.from(scope.periodStartInclusive()));
    values.add(Timestamp.from(scope.periodEndExclusive()));
    Boolean blocked =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM analytics.disclosure_reservation prior "
                + "WHERE prior.state IN ("
                + statePlaceholders
                + ") AND prior.operation_id <> ? "
                + "AND EXISTS (SELECT 1 FROM analytics.disclosure_reservation_lineage prior_lineage "
                + "WHERE prior_lineage.reservation_id = prior.reservation_id "
                + "AND prior_lineage.lineage_id IN ("
                + lineagePlaceholders
                + ")) AND (prior.scope_indicator_key IS NULL "
                + "OR prior.scope_indicator_version IS NULL OR ("
                + "prior.scope_organization_issuer = ? "
                + "AND prior.scope_organization_id = ? "
                + "AND prior.scope_period_start_utc < ? "
                + "AND prior.scope_period_end_exclusive_utc > ? "
                + "AND (prior.scope_indicator_key <> ? "
                + "OR prior.scope_indicator_version <> ? "
                + "OR prior.scope_facility_id IS DISTINCT FROM ? "
                + "OR prior.scope_period_start_utc <> ? "
                + "OR prior.scope_period_end_exclusive_utc <> ?))))",
            Boolean.class,
            values.toArray());
    return Boolean.TRUE.equals(blocked);
  }

  private void insertReservationLineages(UUID reservationId, Set<UUID> lineageIds) {
    lineageIds.stream()
        .sorted()
        .forEach(
            lineage ->
                jdbc.update(
                    "INSERT INTO analytics.disclosure_reservation_lineage (reservation_id, lineage_id) "
                        + "VALUES (?, ?)",
                    reservationId,
                    lineage));
  }

  private void insertOutputs(
      UUID reservationId, List<DisclosureOutput> outputs, Map<String, Long> cells) {
    outputs.stream()
        .sorted(Comparator.comparing(DisclosureOutput::outputKey))
        .forEach(
            output -> {
              jdbc.update(
                  "INSERT INTO analytics.disclosure_reservation_output "
                      + "(reservation_id, output_key, output_value, equation_result, "
                      + "applies_minimum_cell_count) VALUES (?, ?, ?, ?, ?)",
                  reservationId,
                  output.outputKey(),
                  output.value(),
                  output.equationResult(),
                  output.appliesMinimumCellCount());
              output.cellCoefficients().entrySet().stream()
                  .sorted(Map.Entry.comparingByKey())
                  .forEach(
                      term ->
                          jdbc.update(
                              "INSERT INTO analytics.disclosure_reservation_term "
                                  + "(reservation_id, output_key, cell_key, coefficient) VALUES (?, ?, ?, ?)",
                              reservationId,
                              output.outputKey(),
                              term.getKey(),
                              term.getValue()));
            });
    cells.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            cell ->
                jdbc.update(
                    "INSERT INTO analytics.disclosure_reservation_cell "
                        + "(reservation_id, cell_key, cell_value) VALUES (?, ?, ?)",
                    reservationId,
                    cell.getKey(),
                    cell.getValue()));
  }

  private long bumpCheckpoint(String policyKey, Instant changedAt) {
    List<Long> result =
        jdbc.query(
            "UPDATE analytics.disclosure_policy_head SET history_checkpoint = history_checkpoint + 1, "
                + "updated_at = ? WHERE policy_key = ? RETURNING history_checkpoint",
            (row, rowNumber) -> row.getLong("history_checkpoint"),
            Timestamp.from(changedAt),
            policyKey);
    if (result.size() != 1) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.POLICY_NOT_FOUND);
    }
    return result.getFirst();
  }

  private Optional<DisclosureReservation> findByOperation(String policyKey, UUID operationId) {
    List<DisclosureReservation> rows =
        jdbc.query(
            "SELECT reservation_id, policy_key, policy_version, operation_id, operation_fingerprint, "
                + "history_checkpoint, state, reason_code, created_at, updated_at "
                + "FROM analytics.disclosure_reservation WHERE policy_key = ? AND operation_id = ?",
            RESERVATION_MAPPER,
            policyKey,
            operationId);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    DisclosureReservation row = rows.getFirst();
    return Optional.of(withLineages(row, loadReservationLineages(row.reservationId()), false));
  }

  private Optional<DisclosureReservation> findReservationForUpdate(UUID reservationId) {
    List<DisclosureReservation> rows =
        jdbc.query(
            "SELECT reservation_id, policy_key, policy_version, operation_id, operation_fingerprint, "
                + "history_checkpoint, state, reason_code, created_at, updated_at "
                + "FROM analytics.disclosure_reservation WHERE reservation_id = ? FOR UPDATE",
            RESERVATION_MAPPER,
            reservationId);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    DisclosureReservation row = rows.getFirst();
    return Optional.of(withLineages(row, loadReservationLineages(reservationId), false));
  }

  private Set<UUID> loadReservationLineages(UUID reservationId) {
    return Set.copyOf(
        jdbc.query(
            "SELECT lineage_id FROM analytics.disclosure_reservation_lineage "
                + "WHERE reservation_id = ? ORDER BY lineage_id",
            (result, rowNumber) -> result.getObject("lineage_id", UUID.class),
            reservationId));
  }

  private DisclosureReleaseContext replayContext(
      DisclosureCandidate candidate, DisclosureReservation existing) {
    if (!existing.operationFingerprint().equals(candidate.fingerprint())) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.OPERATION_ID_REUSED);
    }
    PolicyHead head =
        findHead(candidate.policyKey(), false)
            .orElseThrow(
                () ->
                    new DisclosureReservationException(
                        DisclosureReservationException.Reason.POLICY_NOT_FOUND));
    return new DisclosureReleaseContext(
        loadPolicyVersion(head, existing.policyVersion()),
        existing.lineageIds(),
        List.of(),
        Map.of(),
        true,
        existing.historyCheckpoint(),
        Optional.of(existing));
  }

  private void validateLockedContext(
      DisclosureCandidate candidate, DisclosureReleaseContext lockedHistory) {
    if (!candidate.policyKey().equals(lockedHistory.policy().policyKey())
        || candidate.expectedPolicyVersion() != lockedHistory.policy().version()
        || !candidateLineages(lockedHistory.policy(), candidate)
            .equals(lockedHistory.lineageIds())) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.POLICY_VERSION_CONFLICT);
    }
  }

  private static DisclosureReservation replay(
      DisclosureReservation prior, String attemptedFingerprint) {
    if (!prior.operationFingerprint().equals(attemptedFingerprint)) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.OPERATION_ID_REUSED);
    }
    return withLineages(prior, prior.lineageIds(), true);
  }

  private static DisclosureReservation withLineages(
      DisclosureReservation prior, Set<UUID> lineages, boolean replayed) {
    return new DisclosureReservation(
        prior.reservationId(),
        prior.policyKey(),
        prior.policyVersion(),
        prior.operationId(),
        prior.operationFingerprint(),
        lineages,
        prior.historyCheckpoint(),
        prior.state(),
        prior.reasonCode(),
        prior.createdAt(),
        prior.updatedAt(),
        replayed);
  }

  private static DisclosureReservation reservation(
      ResultSet result, boolean replayed, Set<UUID> lineages) throws SQLException {
    return new DisclosureReservation(
        result.getObject("reservation_id", UUID.class),
        result.getString("policy_key"),
        result.getInt("policy_version"),
        result.getObject("operation_id", UUID.class),
        result.getString("operation_fingerprint"),
        lineages,
        result.getLong("history_checkpoint"),
        DisclosureReservationState.valueOf(result.getString("state")),
        result.getString("reason_code"),
        instant(result, "created_at"),
        instant(result, "updated_at"),
        replayed);
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    Timestamp timestamp = result.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static boolean allowedTransition(
      DisclosureReservationState current, DisclosureReservationState target) {
    return switch (current) {
      case RESERVED ->
          target == DisclosureReservationState.DISCLOSED
              || target == DisclosureReservationState.POTENTIALLY_DISCLOSED
              || target == DisclosureReservationState.WITHDRAWN_NOT_SENT;
      case POTENTIALLY_DISCLOSED ->
          target == DisclosureReservationState.DISCLOSED
              || target == DisclosureReservationState.WITHDRAWN_NOT_SENT;
      case DISCLOSED, WITHDRAWN_NOT_SENT, WITHHELD, REVIEW_REQUIRED -> false;
    };
  }

  private static void validatePolicyOwner(PolicyHead head, DisclosurePolicyVersion proposed) {
    if (!head.organizationIssuer().equals(proposed.organization().issuer().value())
        || !head.organizationReferenceType().equals(proposed.organization().resourceType())
        || !head.organizationId().equals(UUID.fromString(proposed.organization().value()))
        || !head.conservativeLineageId().equals(proposed.conservativeLineageId())) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.LINEAGE_INVALID);
    }
  }

  private record PolicyHead(
      String policyKey,
      String organizationIssuer,
      String organizationReferenceType,
      UUID organizationId,
      UUID conservativeLineageId,
      int activeVersion,
      long historyCheckpoint) {}

  private record PolicyVersionHeader(
      long minimumCellCount, boolean externalBaselineComplete, String requestedFingerprint) {}

  private record StoredOutput(
      UUID reservationId,
      String outputKey,
      long value,
      long equationResult,
      boolean appliesMinimumCellCount,
      DisclosureReservationState state) {}

  private record StoredReviewOutput(
      String outputKey,
      long value,
      long equationResult,
      boolean appliesMinimumCellCount) {}

  private record ReviewResolution(
      DisclosureReservationState state, UUID evidenceReference) {}

  private record OutputIdentity(UUID reservationId, String outputKey) {}

  private record History(
      List<DisclosureOutput> outputs,
      Map<String, Long> cellValues,
      boolean complete,
      List<DisclosureOutput> potentiallyDisclosedOutputs) {}

  private static final class InconsistentHistoryException extends RuntimeException {}
}
