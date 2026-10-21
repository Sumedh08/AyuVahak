package org.ayuvahak.platform.operatorsupport.application;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Versioned finite allowlist of reviewed documentation excerpts and topic-to-source mappings. */
public final class OperatorSupportCorpus {

  public static final String APPROVED_VERSION = "operator-support-corpus-v1";

  private final String version;
  private final Map<OperatorSupportQuestionKey, List<ApprovedOperatorSupportSource>> byQuestion;

  private OperatorSupportCorpus(
      String version,
      Map<OperatorSupportQuestionKey, List<ApprovedOperatorSupportSource>> sources) {
    if (version == null || !version.matches("[a-z0-9-]{1,80}")) {
      throw new IllegalArgumentException("corpus version must be a stable identifier");
    }
    Objects.requireNonNull(sources, "sources");
    EnumMap<OperatorSupportQuestionKey, List<ApprovedOperatorSupportSource>> copy =
        new EnumMap<>(OperatorSupportQuestionKey.class);
    for (OperatorSupportQuestionKey key : OperatorSupportQuestionKey.values()) {
      List<ApprovedOperatorSupportSource> questionSources =
          List.copyOf(sources.getOrDefault(key, List.of()));
      if (questionSources.size() > 4
          || questionSources.stream()
                  .map(ApprovedOperatorSupportSource::sourceId)
                  .distinct()
                  .count()
              != questionSources.size()) {
        throw new IllegalArgumentException("question sources must be unique and bounded");
      }
      copy.put(key, questionSources);
    }
    if (sources.size() != copy.size()) {
      throw new IllegalArgumentException("corpus contains an unknown question key");
    }
    this.version = version;
    this.byQuestion = Map.copyOf(copy);
  }

  public static OperatorSupportCorpus approved() {
    ApprovedOperatorSupportSource capacity =
        ApprovedOperatorSupportSource.fromExcerpt(
            "CAPACITY_REMOTE_EVIDENCE_FRESHNESS",
            "docs/architecture/capacity-readiness-policy-v1.md",
            "Freshness states; Source policy and current projection",
            "STALE evidence exists but exceeds the configured decision age or otherwise fails current-freshness policy. It cannot support positive availability/readiness or new ranking. UNKNOWN means no valid usable evidence or policy is available for the question. It is never interpreted as zero/available/ready. Do not produce numeric zero unless zero was explicitly observed as a valid typed quantity.");
    ApprovedOperatorSupportSource federation =
        ApprovedOperatorSupportSource.fromExcerpt(
            "FEDERATION_UNKNOWN_OUTCOME",
            "docs/operations/phase-13-runbooks.md",
            "Phase 9 exchange and federation outage or unknown outcome",
            "Keep owner-local state authoritative. Do not turn timeout into rejection or success, do not send a new operation identity for the same logical action, and do not retry a possibly committed operation unless its frozen contract explicitly allows safe replay. On peer suspension/revocation, stop queued sends and retries. If the outcome cannot be established, retain the unknown state and block conflicting work under the owner invariant. A human may reconcile only through the documented owner command with audit evidence.");
    ApprovedOperatorSupportSource transfer =
        ApprovedOperatorSupportSource.fromExcerpt(
            "TRANSFER_JOURNEY_LIFECYCLE",
            "docs/architecture/volume-2-state-machines.md",
            "Transfer",
            "READY means a current Capacity coordination hold was confirmed; it is not an admission or guarantee against a later risk event. HANDED_OVER requires a distinct receiving acknowledgement. COMPLETED closes the coordination workflow; it does not record clinical disposition.");
    ApprovedOperatorSupportSource dhis2 =
        ApprovedOperatorSupportSource.fromExcerpt(
            "ANALYTICS_DHIS2_EXPORT_OPERATION",
            "docs/architecture/volume-4-state-machines.md",
            "DHIS2 export operation",
            "Response loss may have applied the value; no blind duplicate overwrite. Authorized read-back/operator resolves this operation only; any correction is a new immutable revision, operation identity and disclosure reservation. `ignored=1` requires authorized read-back to confirm the expected target value before acknowledgment.");
    ApprovedOperatorSupportSource database =
        ApprovedOperatorSupportSource.fromExcerpt(
            "DATABASE_RESTORE_RUNBOOK",
            "docs/operations/phase-13-runbooks.md",
            "Database outage, corruption, and restore",
            "Restore only to an isolated clean target from the deployment's verified backup/WAL set. Validate the selected restore point and schema. Reapply each available owner manifest through that owner's procedure. If the deployment's completed retention actions exceed the implemented scope, do not admit the restored instance until an owner-approved reconciliation is completed. Require configured restore receipts, successful Flyway validation, representative scoped reads, audit/idempotency/outbox checks, and unresolved federation/Analytics operation barriers before resuming admission.");
    ApprovedOperatorSupportSource identity =
        ApprovedOperatorSupportSource.fromExcerpt(
            "IDENTITY_PROVIDER_OUTAGE_RUNBOOK",
            "docs/operations/phase-13-runbooks.md",
            "Identity provider outage",
            "Do not bypass signature, issuer, audience, expiry, or grant checks. New or invalidly authenticated requests remain denied. Keep database liveness separate from the IdP. Follow the deployment's bounded JWKS cache policy; do not extend it manually without security-owner approval.");
    ApprovedOperatorSupportSource rateLimits =
        ApprovedOperatorSupportSource.fromExcerpt(
            "PRODUCTION_REQUEST_LIMITS",
            "docs/development/production-deployment-profiles.md",
            "Production request and SSE limits",
            "Application request and SSE limits are bounded per process. In the HA profile, an operator must account for replica count when selecting each per-process budget and configure an ingress/gateway limit if a deployment-wide aggregate ceiling is required. IP-based edge limits supplement authenticated principal/service limits; neither replaces authorization. AyuVahak does not depend on a distributed cache for these controls.");

    EnumMap<OperatorSupportQuestionKey, List<ApprovedOperatorSupportSource>> mappings =
        new EnumMap<>(OperatorSupportQuestionKey.class);
    mappings.put(OperatorSupportQuestionKey.CAPACITY_STALENESS, List.of(capacity));
    mappings.put(OperatorSupportQuestionKey.FEDERATION_OUTCOME_UNKNOWN, List.of(federation));
    mappings.put(OperatorSupportQuestionKey.TRANSFER_RECONCILIATION, List.of(federation, transfer));
    mappings.put(OperatorSupportQuestionKey.DHIS2_UNKNOWN_OUTCOME, List.of(dhis2));
    mappings.put(OperatorSupportQuestionKey.DATABASE_RESTORE, List.of(database));
    mappings.put(OperatorSupportQuestionKey.IDENTITY_PROVIDER_OUTAGE, List.of(identity));
    mappings.put(OperatorSupportQuestionKey.RATE_LIMIT_OPERATION, List.of(rateLimits));
    mappings.put(OperatorSupportQuestionKey.UNSUPPORTED_TOPIC, List.of());
    return new OperatorSupportCorpus(APPROVED_VERSION, mappings);
  }

  public String version() {
    return version;
  }

  public List<ApprovedOperatorSupportSource> sourcesFor(OperatorSupportQuestionKey questionKey) {
    Objects.requireNonNull(questionKey, "question key");
    return byQuestion.get(questionKey);
  }
}
