package org.ayuvahak.directory.adapter.mcsd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.context.FhirContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.ayuvahak.directory.adapter.configuration.DirectoryMcsdSourceProperties.SourceBinding;
import org.ayuvahak.directory.application.DirectoryMcsdResourceType;
import org.ayuvahak.directory.application.DirectoryMcsdSourceQueryPort;
import org.ayuvahak.directory.application.DirectoryMcsdSourceQueryPurpose;
import org.ayuvahak.directory.application.DirectoryMcsdSourceUnavailableException;
import org.ayuvahak.directory.application.DirectorySourceProjection;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.application.PartnerOutboundReadPort;
import org.ayuvahak.platform.exchange.application.PartnerOutboundReadRequest;
import org.ayuvahak.platform.exchange.application.PartnerOutboundReadResponse;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.fhir.FhirPayloadLimits;
import org.ayuvahak.platform.fhir.FhirR4PayloadParser;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Organization;
import org.junit.jupiter.api.Test;

class PartnerDirectoryMcsdSourceQueryAdapterTest {

  private static final IssuerId SOURCE = new IssuerId("urn:synthetic:mcsd:directory-a");
  private static final String ORGANIZATION_PROFILE =
      "https://profiles.ihe.net/ITI/mCSD/StructureDefinition/IHE.mCSD.Organization|4.0.0";
  private static final PartnerTrustConfiguration.AllowedOperation SEARCH_OPERATION =
      operation("mcsd.iti90.search");

  @Test
  void parserAcceptsTheSyntheticSearchBundleBeforeDirectoryMapping() {
    byte[] payload = response(bundle(Bundle.BundleType.SEARCHSET, organization(true), null));

    var parsed =
        FhirR4PayloadParser.withCoreProfiles(FhirPayloadLimits.conservativeDefaults())
            .parse(
                payload,
                "application/fhir+json",
                new org.ayuvahak.platform.fhir.FhirProfileContext(
                    "4.0.1", "http://hl7.org/fhir/StructureDefinition/Bundle|4.0.1"));

    assertThat(parsed.resource()).isInstanceOf(Bundle.class);
  }

  @Test
  void queriesOnlyTheConfiguredPartnerAndMapsAValidatedSearchBundle() {
    AtomicReference<PartnerOutboundReadRequest> observed = new AtomicReference<>();
    PartnerDirectoryMcsdSourceQueryAdapter adapter =
        adapter(
            List.of(new SourceBinding(SOURCE.value(), "SYNTHETIC-DIRECTORY")),
            request -> {
              observed.set(request);
              return response(
                  response(bundle(Bundle.BundleType.SEARCHSET, organization(true), null)));
            });

    var page =
        adapter.search(
            new DirectoryMcsdSourceQueryPort.SearchRequest(
                executionContext(),
                DirectoryMcsdSourceQueryPurpose.INITIAL_DISCOVERY,
                SOURCE,
                Set.of(DirectoryMcsdResourceType.ORGANIZATION),
                Optional.empty(),
                100));

    assertThat(observed.get().partnerKey()).isEqualTo(new PartnerKey("SYNTHETIC-DIRECTORY"));
    assertThat(observed.get().operation()).isEqualTo(SEARCH_OPERATION);
    assertThat(observed.get().pathSegments()).isEmpty();
    assertThat(observed.get().queryParameters())
        .containsEntry("_type", List.of("Organization"))
        .containsEntry("_count", List.of("100"));
    assertThat(observed.get().continuationLink()).isEmpty();
    assertThat(page.complete()).isTrue();
    assertThat(page.projections()).hasSize(1);
    DirectorySourceProjection.OrganizationFact mapped =
        (DirectorySourceProjection.OrganizationFact) page.projections().getFirst();
    assertThat(mapped.name()).isEqualTo("Synthetic Directory Authority");
    assertThat(mapped.metadata().sourceIdentity().issuer()).isEqualTo(SOURCE);
  }

  @Test
  void acceptsSupportedFhirJsonContentTypeParameters() {
    PartnerDirectoryMcsdSourceQueryAdapter adapter =
        adapter(
            List.of(new SourceBinding(SOURCE.value(), "SYNTHETIC-DIRECTORY")),
            request ->
                response(
                    response(bundle(Bundle.BundleType.SEARCHSET, organization(true), null)),
                    "application/fhir+json; charset=utf-8; fhirVersion=4.0"));

    var page =
        adapter.search(
            new DirectoryMcsdSourceQueryPort.SearchRequest(
                executionContext(),
                DirectoryMcsdSourceQueryPurpose.INITIAL_DISCOVERY,
                SOURCE,
                Set.of(DirectoryMcsdResourceType.ORGANIZATION),
                Optional.empty(),
                100));

    assertThat(page.projections()).hasSize(1);
  }

  @Test
  void carriesSourceContinuationAsOpaqueDataWithoutTreatingItAsDestination() {
    String next =
        "https://directory.partner.test/fhir?_getpages=synthetic-page&_getpagesoffset=100";
    AtomicReference<PartnerOutboundReadRequest> observed = new AtomicReference<>();
    PartnerDirectoryMcsdSourceQueryAdapter adapter =
        adapter(
            List.of(new SourceBinding(SOURCE.value(), "SYNTHETIC-DIRECTORY")),
            request -> {
              observed.set(request);
              return response(
                  response(bundle(Bundle.BundleType.SEARCHSET, organization(true), next)));
            });

    var page =
        adapter.search(
            new DirectoryMcsdSourceQueryPort.SearchRequest(
                executionContext(),
                DirectoryMcsdSourceQueryPurpose.INITIAL_DISCOVERY,
                SOURCE,
                Set.of(DirectoryMcsdResourceType.ORGANIZATION),
                Optional.of(next),
                100));

    assertThat(observed.get().partnerKey()).isEqualTo(new PartnerKey("SYNTHETIC-DIRECTORY"));
    assertThat(observed.get().continuationLink()).contains(next);
    assertThat(page.complete()).isFalse();
    assertThat(page.nextPageToken()).contains(next);
  }

  @Test
  void missingSourceBindingAndTransportFailureNeverFallBackToAnotherPartner() {
    PartnerDirectoryMcsdSourceQueryAdapter unbound =
        adapter(List.of(), request -> response(new byte[0]));

    assertThatThrownBy(
            () ->
                unbound.search(
                    new DirectoryMcsdSourceQueryPort.SearchRequest(
                        executionContext(),
                        DirectoryMcsdSourceQueryPurpose.INITIAL_DISCOVERY,
                        SOURCE,
                        Set.of(DirectoryMcsdResourceType.ORGANIZATION),
                        Optional.empty(),
                        100)))
        .isInstanceOf(DirectoryMcsdSourceUnavailableException.class);

    PartnerDirectoryMcsdSourceQueryAdapter unavailable =
        adapter(
            List.of(new SourceBinding(SOURCE.value(), "SYNTHETIC-DIRECTORY")),
            request -> {
              throw new org.ayuvahak.platform.exchange.application.PartnerTransportException(
                  org.ayuvahak.platform.exchange.application.PartnerTransportFailure.DNS_FAILURE);
            });
    assertThatThrownBy(
            () ->
                unavailable.search(
                    new DirectoryMcsdSourceQueryPort.SearchRequest(
                        executionContext(),
                        DirectoryMcsdSourceQueryPurpose.INITIAL_DISCOVERY,
                        SOURCE,
                        Set.of(DirectoryMcsdResourceType.ORGANIZATION),
                        Optional.empty(),
                        100)))
        .isInstanceOf(DirectoryMcsdSourceUnavailableException.class);
  }

  @Test
  void sourceAvailabilityChecksTheBoundPartnerOperationWithoutMakingARequest() {
    List<PartnerTrustConfiguration.AllowedOperation> checkedOperations =
        new java.util.ArrayList<>();
    PartnerOutboundReadPort outbound =
        new PartnerOutboundReadPort() {
          @Override
          public void requireActive(
              PartnerKey partnerKey, PartnerTrustConfiguration.AllowedOperation operation) {
            assertThat(partnerKey).isEqualTo(new PartnerKey("SYNTHETIC-DIRECTORY"));
            checkedOperations.add(operation);
          }

          @Override
          public PartnerOutboundReadResponse read(PartnerOutboundReadRequest request) {
            throw new AssertionError("source availability validation must not perform a request");
          }
        };
    DirectoryMcsdMappingPolicy policy =
        new DirectoryMcsdMappingPolicy(
            Map.of("Organization", Set.of(ORGANIZATION_PROFILE)), Set.of(), Set.of());
    PartnerDirectoryMcsdSourceQueryAdapter adapter =
        new PartnerDirectoryMcsdSourceQueryAdapter(
            new DirectoryMcsdPartnerBindings(
                List.of(new SourceBinding(SOURCE.value(), "SYNTHETIC-DIRECTORY"))),
            policy,
            new DirectoryMcsdMapper(policy),
            FhirR4PayloadParser.withCoreProfiles(FhirPayloadLimits.conservativeDefaults()),
            outbound);

    adapter.requireSourceAvailable(SOURCE, DirectoryMcsdSourceQueryPurpose.INITIAL_DISCOVERY);
    adapter.requireSourceAvailable(SOURCE, DirectoryMcsdSourceQueryPurpose.INCREMENTAL_HISTORY);

    assertThat(checkedOperations)
        .containsExactly(operation("mcsd.iti90.search"), operation("mcsd.iti91.history"));

    PartnerDirectoryMcsdSourceQueryAdapter revoked =
        new PartnerDirectoryMcsdSourceQueryAdapter(
            new DirectoryMcsdPartnerBindings(
                List.of(new SourceBinding(SOURCE.value(), "SYNTHETIC-DIRECTORY"))),
            policy,
            new DirectoryMcsdMapper(policy),
            FhirR4PayloadParser.withCoreProfiles(FhirPayloadLimits.conservativeDefaults()),
            new PartnerOutboundReadPort() {
              @Override
              public void requireActive(
                  PartnerKey partnerKey, PartnerTrustConfiguration.AllowedOperation operation) {
                throw new org.ayuvahak.platform.exchange.application.PartnerTransportException(
                    org.ayuvahak.platform.exchange.application.PartnerTransportFailure
                        .PROFILE_NOT_ACTIVE);
              }

              @Override
              public PartnerOutboundReadResponse read(PartnerOutboundReadRequest request) {
                throw new AssertionError("revoked partner must never be contacted");
              }
            });
    assertThatThrownBy(
            () ->
                revoked.requireSourceAvailable(
                    SOURCE, DirectoryMcsdSourceQueryPurpose.INITIAL_DISCOVERY))
        .isInstanceOf(DirectoryMcsdSourceUnavailableException.class);
  }

  @Test
  void historyUsesTheApprovedSystemHistoryPathAndReturnsOnlyACompleteTimestampCursor() {
    AtomicReference<PartnerOutboundReadRequest> observed = new AtomicReference<>();
    PartnerDirectoryMcsdSourceQueryAdapter adapter =
        adapter(
            List.of(new SourceBinding(SOURCE.value(), "SYNTHETIC-DIRECTORY")),
            request -> {
              observed.set(request);
              return response(
                  response(
                      bundle(
                          Bundle.BundleType.HISTORY,
                          organization(false),
                          null,
                          "2026-10-15T10:30:00Z")));
            });

    var page =
        adapter.history(
            new DirectoryMcsdSourceQueryPort.HistoryRequest(
                executionContext(),
                DirectoryMcsdSourceQueryPurpose.INCREMENTAL_HISTORY,
                SOURCE,
                "2026-10-14T10:00:00Z",
                Optional.empty(),
                100));

    assertThat(observed.get().operation()).isEqualTo(operation("mcsd.iti91.history"));
    assertThat(observed.get().pathSegments()).containsExactly("_history");
    assertThat(observed.get().queryParameters())
        .containsEntry("_since", List.of("2026-10-14T10:00:00Z"));
    assertThat(page.complete()).isTrue();
    assertThat(page.nextCursor()).contains("2026-10-15T10:30:00Z");
    assertThat(
            ((DirectorySourceProjection.OrganizationFact) page.projections().getFirst()).active())
        .isFalse();
  }

  private static PartnerDirectoryMcsdSourceQueryAdapter adapter(
      List<SourceBinding> bindings,
      java.util.function.Function<PartnerOutboundReadRequest, PartnerOutboundReadResponse> read) {
    Map<String, Set<String>> profiles =
        Map.of(
            "Organization", Set.of(ORGANIZATION_PROFILE),
            "Location", Set.of("urn:synthetic:profile:mcsd:location"),
            "HealthcareService", Set.of("urn:synthetic:profile:mcsd:healthcare-service"),
            "Endpoint", Set.of("urn:synthetic:profile:mcsd:endpoint"));
    DirectoryMcsdMappingPolicy policy =
        new DirectoryMcsdMappingPolicy(profiles, Set.of(), Set.of());
    return new PartnerDirectoryMcsdSourceQueryAdapter(
        new DirectoryMcsdPartnerBindings(bindings),
        policy,
        new DirectoryMcsdMapper(policy),
        FhirR4PayloadParser.withCoreProfiles(FhirPayloadLimits.conservativeDefaults()),
        new PartnerOutboundReadPort() {
          @Override
          public void requireActive(
              PartnerKey partnerKey, PartnerTrustConfiguration.AllowedOperation operation) {}

          @Override
          public PartnerOutboundReadResponse read(PartnerOutboundReadRequest request) {
            return read.apply(request);
          }
        });
  }

  private static PartnerOutboundReadResponse response(byte[] body) {
    return new PartnerOutboundReadResponse(200, Optional.of("application/fhir+json"), body, 1);
  }

  private static PartnerOutboundReadResponse response(byte[] body, String contentType) {
    return new PartnerOutboundReadResponse(200, Optional.of(contentType), body, 1);
  }

  private static byte[] response(Bundle bundle) {
    return FhirContext.forR4Cached()
        .newJsonParser()
        .encodeResourceToString(bundle)
        .getBytes(StandardCharsets.UTF_8);
  }

  private static Bundle bundle(Bundle.BundleType type, Organization resource, String next) {
    return bundle(type, resource, next, "2026-10-15T10:30:00Z");
  }

  private static Bundle bundle(
      Bundle.BundleType type, Organization resource, String next, String timestamp) {
    Bundle bundle = new Bundle();
    bundle.setType(type);
    bundle.setTimestamp(java.util.Date.from(Instant.parse(timestamp)));
    Bundle.BundleEntryComponent entry =
        bundle
            .addEntry()
            .setFullUrl("urn:uuid:00000000-0000-4000-8000-000000000001")
            .setResource(resource);
    if (type == Bundle.BundleType.SEARCHSET) {
      bundle
          .addLink()
          .setRelation("self")
          .setUrl("https://directory.partner.test/fhir?_type=Organization&_count=100");
      entry.setSearch(
          new Bundle.BundleEntrySearchComponent().setMode(Bundle.SearchEntryMode.MATCH));
    } else if (type == Bundle.BundleType.HISTORY) {
      entry.setRequest(
          new Bundle.BundleEntryRequestComponent()
              .setMethod(Bundle.HTTPVerb.PUT)
              .setUrl("Organization/SYN-ORG-001"));
      entry.setResponse(
          new Bundle.BundleEntryResponseComponent().setStatus("200 OK").setEtag("W/\"v1\""));
    }
    if (next != null) {
      bundle.addLink().setRelation("next").setUrl(next);
    }
    return bundle;
  }

  private static Organization organization(boolean active) {
    Organization organization = new Organization();
    organization.setId("SYN-ORG-001");
    organization.setActive(active);
    organization.setName("Synthetic Directory Authority");
    organization
        .addType()
        .addCoding()
        .setSystem("http://terminology.hl7.org/CodeSystem/organization-type")
        .setCode("dept")
        .setDisplay("Department");
    Meta meta = new Meta();
    meta.addProfile(ORGANIZATION_PROFILE);
    meta.setVersionId("v1");
    organization.setMeta(meta);
    return organization;
  }

  private static ExecutionContext executionContext() {
    return new ExecutionContextFactory((actor, scope) -> true)
        .forDeploymentBoundedBackgroundWork(
            ActorReference.service(new IssuerId("urn:synthetic:ayuvahak"), "directory-test"),
            new CorrelationId("directory-mcsd-test"));
  }

  private static PartnerTrustConfiguration.AllowedOperation operation(String code) {
    return new PartnerTrustConfiguration.AllowedOperation(
        PartnerTrustConfiguration.OwnerContext.DIRECTORY,
        code,
        PartnerTrustConfiguration.Direction.OUTBOUND,
        PartnerTrustConfiguration.Protocol.FHIR_R4_JSON,
        "IHE-mCSD");
  }
}
