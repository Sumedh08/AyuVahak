package org.ayuvahak.directory.adapter.federation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.directory.application.DirectoryFederatedDiscoveryInvalidResponseException;
import org.ayuvahak.directory.application.DirectoryFederatedDiscoveryPage;
import org.ayuvahak.directory.application.DirectoryFederatedDiscoveryPort;
import org.ayuvahak.directory.application.DirectoryFederatedDiscoveryQuery;
import org.ayuvahak.directory.application.DirectoryFederatedDiscoveryUnavailableException;
import org.ayuvahak.directory.application.DirectoryFederatedFact;
import org.ayuvahak.directory.application.DirectoryRemoteResourceReference;
import org.ayuvahak.platform.exchange.application.FederationOperationFingerprint;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationException;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationPort;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationRequest;
import org.ayuvahak.platform.federation.application.DeploymentIdentityRegistrationService;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Uses only the Phase 9 trusted exchange port for the fixed Directory discovery operation. */
@Component
@Lazy
public final class Phase9DirectoryFederatedDiscoveryAdapter
    implements DirectoryFederatedDiscoveryPort {

  private static final int MAX_RESPONSE_BYTES = 262_144;
  private static final String PROTOCOL_PROFILE = "AyuVahak-Federation-v1";

  private final FederationPeerOperationPort exchange;
  private final DeploymentIdentityRegistrationService deploymentIdentity;
  private final ObjectMapper mapper;

  public Phase9DirectoryFederatedDiscoveryAdapter(
      FederationPeerOperationPort exchange,
      DeploymentIdentityRegistrationService deploymentIdentity,
      ObjectMapper mapper) {
    this.exchange = Objects.requireNonNull(exchange, "exchange");
    this.deploymentIdentity = Objects.requireNonNull(deploymentIdentity, "deploymentIdentity");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public DirectoryFederatedDiscoveryPage discover(DirectoryFederatedDiscoveryQuery query) {
    Objects.requireNonNull(query, "query");
    DeploymentId localDeployment =
        deploymentIdentity
            .current()
            .map(identity -> identity.deploymentId())
            .orElseThrow(DirectoryFederatedDiscoveryUnavailableException::new);
    byte[] requestBody = encodeRequest(query, localDeployment);
    var operation =
        org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation
            .directoryRemoteDiscovery(
                org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction.OUTBOUND);
    var fingerprint =
        FederationOperationFingerprint.calculate(
            localDeployment, query.peerDeployment(), query.remoteScope(), operation, requestBody);
    var response =
        execute(
            new FederationPeerOperationRequest(
                query.partnerKey(),
                localDeployment,
                query.peerDeployment(),
                query.remoteScope(),
                operation,
                query.operationId(),
                query.partnerConfigurationVersion(),
                requestBody,
                fingerprint,
                query.correlationId()));
    if (response.body().length > MAX_RESPONSE_BYTES
        || response.configurationVersion() != query.partnerConfigurationVersion()
        || response.contentType().isEmpty()
        || !isJson(response.contentType().orElseThrow())) {
      throw new DirectoryFederatedDiscoveryInvalidResponseException();
    }
    try {
      return decodeResponse(response.body(), query);
    } catch (DirectoryFederatedDiscoveryInvalidResponseException invalid) {
      throw invalid;
    } catch (Exception invalid) {
      throw new DirectoryFederatedDiscoveryInvalidResponseException();
    }
  }

  private org.ayuvahak.platform.exchange.application.PartnerOutboundOperationResponse execute(
      FederationPeerOperationRequest request) {
    try {
      return exchange.execute(request);
    } catch (FederationPeerOperationException unavailable) {
      throw new DirectoryFederatedDiscoveryUnavailableException();
    }
  }

  private byte[] encodeRequest(DirectoryFederatedDiscoveryQuery query, DeploymentId source) {
    try {
      return mapper.writeValueAsBytes(
          Map.of(
              "protocolProfile",
              PROTOCOL_PROFILE,
              "sourceDeploymentId",
              source.value(),
              "requestedScope",
              Map.of(
                  "issuer", query.remoteScope().issuer().value(),
                  "resourceType", query.remoteScope().resourceType(),
                  "value", query.remoteScope().value()),
              "resourceTypes",
              "Organization".equals(query.remoteScope().resourceType())
                  ? List.of("Organization", "Facility")
                  : List.of("Facility"),
              "maximumFacts",
              DirectoryFederatedDiscoveryPage.MAX_FACTS));
    } catch (Exception failure) {
      throw new IllegalStateException("Unable to encode the Directory discovery request.", failure);
    }
  }

  private DirectoryFederatedDiscoveryPage decodeResponse(
      byte[] body, DirectoryFederatedDiscoveryQuery query) throws Exception {
    DirectoryFederatedDiscoveryWireResponse wire =
        mapper.readValue(body, DirectoryFederatedDiscoveryWireResponse.class);
    if (wire.facts() == null || wire.facts().size() > DirectoryFederatedDiscoveryPage.MAX_FACTS) {
      throw new DirectoryFederatedDiscoveryInvalidResponseException();
    }
    if (!PROTOCOL_PROFILE.equals(wire.protocolProfile())
        || !query.peerDeployment().value().equals(wire.sourceDeploymentId())
        || !query.remoteScope().issuer().value().equals(wire.requestedScopeIssuer())
        || !query.remoteScope().resourceType().equals(wire.requestedScopeResourceType())
        || !query.remoteScope().value().equals(wire.requestedScopeValue())) {
      throw new DirectoryFederatedDiscoveryInvalidResponseException();
    }
    Instant observedAt = Instant.parse(wire.observedAt());
    DirectoryFederatedDiscoveryPage.Completeness completeness =
        DirectoryFederatedDiscoveryPage.Completeness.valueOf(wire.completeness());
    List<DirectoryFederatedFact> facts =
        wire.facts().stream()
            .map(fact -> mapFact(fact, query.peerDeployment(), wire.sourcePolicyVersion()))
            .toList();
    return new DirectoryFederatedDiscoveryPage(
        query.peerDeployment(),
        query.remoteScope(),
        wire.sourcePolicyVersion(),
        observedAt,
        completeness,
        facts);
  }

  private static DirectoryFederatedFact mapFact(
      DirectoryFederatedDiscoveryWireResponse.Fact wire,
      DeploymentId source,
      String sourcePolicyVersion) {
    if (!"Organization".equals(wire.resourceType()) && !"Facility".equals(wire.resourceType())) {
      throw new DirectoryFederatedDiscoveryInvalidResponseException();
    }
    Optional<DirectoryRemoteResourceReference> organization =
        Optional.ofNullable(wire.organizationResourceId())
            .map(
                id ->
                    new DirectoryRemoteResourceReference(source, "Directory", "Organization", id));
    return new DirectoryFederatedFact(
        new DirectoryRemoteResourceReference(
            source, "Directory", wire.resourceType(), wire.resourceId()),
        organization,
        wire.displayName(),
        Objects.requireNonNull(wire.active(), "active"),
        Objects.requireNonNull(wire.sourceVersion(), "sourceVersion"),
        Optional.ofNullable(wire.sourceUpdatedAt()).map(Instant::parse),
        sourcePolicyVersion);
  }

  private static boolean isJson(String contentType) {
    String mediaType = contentType.split(";", 2)[0].trim();
    return "application/json".equalsIgnoreCase(mediaType);
  }
}
