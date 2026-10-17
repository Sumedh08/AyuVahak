package org.ayuvahak.matching.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.ayuvahak.matching.domain.RemoteCapacityEvidence;
import org.ayuvahak.matching.domain.RemoteCapacityEvidenceCompatibility;
import org.ayuvahak.matching.domain.RemoteCapacityEvidenceProjector;
import org.ayuvahak.matching.domain.RemoteCapacityOutcome;
import org.ayuvahak.matching.domain.RemoteEvidenceFreshness;
import org.ayuvahak.matching.domain.RemoteFacilityReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.junit.jupiter.api.Test;

class MatchingRemoteCapacityEvidenceQueriesTest {

  private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
  private static final RemoteFacilityReference FACILITY =
      new RemoteFacilityReference(
          new DeploymentId("SYN-DEPLOYMENT-B"), "directory", InternalId.generate());
  private static final RemoteCapacityPeerTarget TARGET =
      new RemoteCapacityPeerTarget(
          new PartnerKey("syn-peer-b"),
          new DeploymentId("SYN-DEPLOYMENT-B"),
          7,
          new RemoteCapacityEvidenceCompatibility("SYN-CAPACITY-PROFILE-1", 4, 2));

  @Test
  void returnsOnlyFreshCompatibleEvidenceFromTheConfiguredPeer() {
    MemoryStore store = new MemoryStore();
    MatchingRemoteCapacityEvidenceQueries queries =
        queries(
            (facility, dimension) -> Optional.of(TARGET),
            (peer, facility, dimension) -> ready(),
            store);

    RemoteCapacityEvidenceQueryResult result = queries.query(FACILITY, "OXYGEN_STATIONS");

    assertThat(result.outcome()).isEqualTo(RemoteCapacityOutcome.READY);
    assertThat(result.freshness()).isEqualTo(RemoteEvidenceFreshness.CURRENT);
    assertThat(result.evidence()).isPresent();
    assertThat(result.evidence().orElseThrow().facility()).isEqualTo(FACILITY);
    assertThat(result.evidence().orElseThrow().quantity())
        .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("4"));
  }

  @Test
  void unconfiguredPeerAndPeerOutageAreExplicitUnknownWithoutFallbackToCachedReady() {
    MemoryStore store = new MemoryStore();
    store.record(ready());
    MatchingRemoteCapacityEvidenceQueries unconfigured =
        queries((facility, dimension) -> Optional.empty(), (peer, remote, code) -> ready(), store);
    MatchingRemoteCapacityEvidenceQueries unavailable =
        queries(
            (facility, dimension) -> Optional.of(TARGET),
            (peer, remote, code) -> {
              throw new RemoteCapacityEvidenceExchangeException(
                  RemoteCapacityEvidenceExchangeException.Reason.TRUST_DENIED);
            },
            store);

    assertUnknown(unconfigured.query(FACILITY, "OXYGEN_STATIONS"), "PEER_NOT_CONFIGURED");
    assertUnknown(unavailable.query(FACILITY, "OXYGEN_STATIONS"), "TRUST_DENIED");
  }

  @Test
  void incompatibleProfileVersionAndExpiredEvidenceCannotAppearReady() {
    RemoteCapacityEvidence changedPolicy =
        new RemoteCapacityEvidence(
            FACILITY,
            "OXYGEN_STATIONS",
            30,
            4,
            3,
            "SYN-CAPACITY-PROFILE-1",
            "SYN-EVIDENCE-030",
            RemoteCapacityOutcome.READY,
            RemoteEvidenceFreshness.CURRENT,
            true,
            Optional.of(new BigDecimal("4")),
            Optional.empty(),
            Optional.empty(),
            Instant.parse("2026-10-18T09:59:30Z"),
            NOW,
            Instant.parse("2026-10-18T10:05:00Z"),
            false);
    MemoryStore policyStore = new MemoryStore();
    MatchingRemoteCapacityEvidenceQueries incompatible =
        queries(
            (facility, dimension) -> Optional.of(TARGET),
            (peer, facility, dimension) -> changedPolicy,
            policyStore);
    RemoteCapacityEvidence expired =
        new RemoteCapacityEvidence(
            FACILITY,
            "OXYGEN_STATIONS",
            31,
            4,
            2,
            "SYN-CAPACITY-PROFILE-1",
            "SYN-EVIDENCE-031",
            RemoteCapacityOutcome.READY,
            RemoteEvidenceFreshness.CURRENT,
            true,
            Optional.of(new BigDecimal("4")),
            Optional.empty(),
            Optional.empty(),
            Instant.parse("2026-10-18T09:59:30Z"),
            NOW,
            NOW,
            false);
    MemoryStore expiredStore = new MemoryStore();
    MatchingRemoteCapacityEvidenceQueries expiredQuery =
        queries(
            (facility, dimension) -> Optional.of(TARGET),
            (peer, facility, dimension) -> expired,
            expiredStore);

    RemoteCapacityEvidenceQueryResult policyResult =
        incompatible.query(FACILITY, "OXYGEN_STATIONS");
    RemoteCapacityEvidenceQueryResult expiredResult =
        expiredQuery.query(FACILITY, "OXYGEN_STATIONS");

    assertThat(policyResult.outcome()).isEqualTo(RemoteCapacityOutcome.UNKNOWN);
    assertThat(policyResult.evidence().orElseThrow().quantity()).isEmpty();
    assertThat(expiredResult.outcome()).isEqualTo(RemoteCapacityOutcome.STALE);
    assertThat(expiredResult.evidence().orElseThrow().quantity()).isEmpty();
  }

  private static MatchingRemoteCapacityEvidenceQueries queries(
      FederatedCapacityPeerPolicy policy,
      RemoteCapacityEvidenceExchange exchange,
      RemoteCapacityEvidenceProjectionStore store) {
    return new MatchingRemoteCapacityEvidenceQueries(
        policy,
        exchange,
        store,
        new RemoteCapacityEvidenceProjector(),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static RemoteCapacityEvidence ready() {
    return new RemoteCapacityEvidence(
        FACILITY,
        "OXYGEN_STATIONS",
        29,
        4,
        2,
        "SYN-CAPACITY-PROFILE-1",
        "SYN-EVIDENCE-029",
        RemoteCapacityOutcome.READY,
        RemoteEvidenceFreshness.CURRENT,
        true,
        Optional.of(new BigDecimal("4")),
        Optional.empty(),
        Optional.empty(),
        Instant.parse("2026-10-18T09:59:30Z"),
        NOW,
        Instant.parse("2026-10-18T10:05:00Z"),
        false);
  }

  private static void assertUnknown(RemoteCapacityEvidenceQueryResult result, String reason) {
    assertThat(result.outcome()).isEqualTo(RemoteCapacityOutcome.UNKNOWN);
    assertThat(result.freshness()).isEqualTo(RemoteEvidenceFreshness.UNKNOWN);
    assertThat(result.evidence()).isEmpty();
    assertThat(result.reasonCode()).isEqualTo(reason);
  }

  private static final class MemoryStore implements RemoteCapacityEvidenceProjectionStore {

    private final Map<String, RemoteCapacityEvidence> values = new HashMap<>();

    @Override
    public RecordResult record(RemoteCapacityEvidence evidence) {
      String key = evidence.facility().toExternalReference() + ":" + evidence.dimensionCode();
      RemoteCapacityEvidence existing = values.get(key);
      if (existing == null || evidence.sourceSequence() > existing.sourceSequence()) {
        values.put(key, evidence);
        return RecordResult.APPLIED;
      }
      if (evidence.sourceSequence() < existing.sourceSequence()) {
        return RecordResult.STALE_IGNORED;
      }
      if (evidence.equals(existing)) {
        return RecordResult.DUPLICATE;
      }
      values.put(key, existing.withSourceConflict(true));
      return RecordResult.CONFLICT;
    }

    @Override
    public Optional<RemoteCapacityEvidence> find(
        RemoteFacilityReference facility, String dimensionCode) {
      return Optional.ofNullable(values.get(facility.toExternalReference() + ":" + dimensionCode));
    }
  }
}
