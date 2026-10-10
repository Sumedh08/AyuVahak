package org.ayuvahak.matching.adapter.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.ayuvahak.matching.domain.RecommendationSnapshot;
import org.ayuvahak.matching.domain.RecommendationSnapshot.CandidateEvidence;
import org.ayuvahak.matching.domain.RecommendationSnapshot.DirectoryCapabilityAssessment;
import org.ayuvahak.matching.domain.RecommendationSnapshot.EvidenceFact;
import org.ayuvahak.matching.domain.RecommendationSnapshot.PolicyVersion;
import org.ayuvahak.platform.core.ExternalReference;

/** API view of the immutable structured evidence; it contains no patient or clinical context. */
public record RecommendationSnapshotRepresentation(
    String id,
    String referralId,
    long referralRevision,
    Instant generatedAt,
    Instant applicableUntil,
    PolicyVersionRepresentation matchingPolicy,
    PolicyVersionRepresentation mappingPolicy,
    List<CapabilityMappingRepresentation> resolvedMappings,
    String outcome,
    List<String> reasonCodes,
    List<CandidateRepresentation> candidates,
    AttributionRepresentation attribution) {

  public static RecommendationSnapshotRepresentation from(RecommendationSnapshot snapshot) {
    return new RecommendationSnapshotRepresentation(
        snapshot.id().toString(),
        snapshot.referralId().toString(),
        snapshot.referralRevision(),
        snapshot.generatedAt(),
        snapshot.applicableUntil(),
        PolicyVersionRepresentation.from(snapshot.matchingPolicy()),
        PolicyVersionRepresentation.from(snapshot.mappingPolicy()),
        snapshot.resolvedMappings().stream().map(CapabilityMappingRepresentation::from).toList(),
        snapshot.outcome().name(),
        snapshot.reasonCodes(),
        snapshot.candidates().stream().map(CandidateRepresentation::from).toList(),
        AttributionRepresentation.from(snapshot.attribution()));
  }

  public record ReferenceRepresentation(String issuer, String resourceType, String identifier) {
    static ReferenceRepresentation from(ExternalReference reference) {
      return new ReferenceRepresentation(
          reference.issuer().value(), reference.resourceType(), reference.value());
    }
  }

  public record PolicyVersionRepresentation(ReferenceRepresentation reference, long version) {
    static PolicyVersionRepresentation from(PolicyVersion version) {
      return new PolicyVersionRepresentation(
          ReferenceRepresentation.from(version.reference()), version.version());
    }
  }

  public record CapabilityMappingRepresentation(String capabilityCode, List<String> dimensions) {
    static CapabilityMappingRepresentation from(
        RecommendationSnapshot.CapabilityDimensionMapping mapping) {
      return new CapabilityMappingRepresentation(
          mapping.capabilityCode(), mapping.dimensionCodes());
    }
  }

  public record CandidateRepresentation(
      ReferenceRepresentation facility,
      ReferenceRepresentation organization,
      String status,
      Integer rank,
      Integer preferenceOrdinal,
      MeasureRepresentation measure,
      List<ReasonRepresentation> reasons,
      List<DirectoryCapabilityAssessmentRepresentation> directoryCapabilityAssessments,
      List<EvidenceRepresentation> evidence) {

    static CandidateRepresentation from(CandidateEvidence candidate) {
      return new CandidateRepresentation(
          ReferenceRepresentation.from(candidate.facility()),
          ReferenceRepresentation.from(candidate.organization()),
          candidate.status().name(),
          candidate.rank().isPresent() ? candidate.rank().getAsInt() : null,
          candidate.preferenceOrdinal().isPresent()
              ? candidate.preferenceOrdinal().getAsInt()
              : null,
          candidate.measure().map(MeasureRepresentation::from).orElse(null),
          candidate.reasons().stream().map(ReasonRepresentation::from).toList(),
          candidate.directoryCapabilityAssessments().stream()
              .map(DirectoryCapabilityAssessmentRepresentation::from)
              .toList(),
          candidate.evidence().stream().map(EvidenceRepresentation::from).toList());
    }
  }

  public record DirectoryCapabilityAssessmentRepresentation(
      String capabilityCode,
      String outcomeCode,
      Long capabilityVersion,
      String capabilityStatus,
      ReferenceRepresentation sourceSystem) {

    static DirectoryCapabilityAssessmentRepresentation from(
        DirectoryCapabilityAssessment assessment) {
      return new DirectoryCapabilityAssessmentRepresentation(
          assessment.capabilityCode(),
          assessment.outcomeCode(),
          assessment.capabilityVersion().isPresent()
              ? assessment.capabilityVersion().getAsLong()
              : null,
          assessment.capabilityStatus().orElse(null),
          ReferenceRepresentation.from(assessment.sourceSystem()));
    }
  }

  public record MeasureRepresentation(
      String kind,
      String sourceCode,
      BigDecimal value,
      String unit,
      Instant observedAt,
      Instant validUntil,
      ReferenceRepresentation sourceSystem) {

    static MeasureRepresentation from(
        org.ayuvahak.matching.domain.RecommendationRanker.Measure measure) {
      return new MeasureRepresentation(
          measure.kind().name(),
          measure.sourceCode(),
          measure.value(),
          measure.unit(),
          measure.observedAt(),
          measure.validUntil(),
          measure.sourceSystem().map(ReferenceRepresentation::from).orElse(null));
    }
  }

  public record ReasonRepresentation(String code, String subjectCode) {
    static ReasonRepresentation from(RecommendationSnapshot.CandidateReason reason) {
      return new ReasonRepresentation(reason.code(), reason.subjectCode().orElse(null));
    }
  }

  public record EvidenceRepresentation(
      String factCode,
      String subjectCode,
      ReferenceRepresentation reference,
      ReferenceRepresentation sourceSystem,
      long sourceVersion,
      Long policyVersion,
      String projectionVersion,
      String outcomeCode,
      BigDecimal value,
      String unit,
      Instant observedAt,
      Instant receivedAt,
      Instant validUntil,
      ReferenceRepresentation provenanceReference) {

    static EvidenceRepresentation from(EvidenceFact fact) {
      return new EvidenceRepresentation(
          fact.factCode(),
          fact.subjectCode(),
          ReferenceRepresentation.from(fact.reference()),
          ReferenceRepresentation.from(fact.sourceSystem()),
          fact.sourceVersion(),
          fact.policyVersion().isPresent() ? fact.policyVersion().getAsLong() : null,
          fact.projectionVersion().orElse(null),
          fact.outcomeCode(),
          fact.value().orElse(null),
          fact.unit().orElse(null),
          fact.observedAt(),
          fact.receivedAt().orElse(null),
          fact.validUntil().orElse(null),
          fact.provenanceReference().map(ReferenceRepresentation::from).orElse(null));
    }
  }

  public record AttributionRepresentation(
      String actorKind,
      ReferenceRepresentation actor,
      ReferenceRepresentation organization,
      ReferenceRepresentation facility,
      String correlationId,
      String purposeCode) {

    static AttributionRepresentation from(RecommendationSnapshot.Attribution attribution) {
      return new AttributionRepresentation(
          attribution.actor().kind().name(),
          ReferenceRepresentation.from(attribution.actor().identity()),
          ReferenceRepresentation.from(attribution.organization()),
          attribution.facility().map(ReferenceRepresentation::from).orElse(null),
          attribution.correlationId().value(),
          attribution.purposeCode());
    }
  }
}
