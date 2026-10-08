package org.ayuvahak.directory.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.ayuvahak.directory.domain.DirectoryAuthorities;
import org.ayuvahak.directory.domain.DirectoryLifecycleStatus;
import org.ayuvahak.directory.domain.DirectoryLocation;
import org.ayuvahak.directory.domain.DirectoryProvenance;
import org.ayuvahak.directory.domain.DirectorySource;
import org.ayuvahak.directory.domain.Facility;
import org.ayuvahak.directory.domain.Organization;
import org.ayuvahak.directory.domain.Wgs84Coordinate;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.audit.application.AuditPersistenceUnavailableException;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditActionKind;
import org.ayuvahak.platform.audit.domain.AuditAuthenticationEvidence;
import org.ayuvahak.platform.audit.domain.AuditAuthorizationOutcome;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.audit.domain.AuditOutcome;
import org.ayuvahak.platform.audit.domain.AuditTargetContext;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.AuthorizationDecision;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Use cases for Directory Organization and Facility facts. */
@Service
@Lazy
public class DirectoryApplicationService {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final int DEFAULT_SEARCH_PAGE_SIZE = 50;
  private static final int MAX_SEARCH_PAGE_SIZE = 100;
  private static final int MAX_SEARCH_OFFSET = 10_000;
  private static final double MAX_SEARCH_RADIUS_METERS = 500_000.0;
  private static final String COMMAND_BUSY_DETAIL =
      "A matching Directory command is still being processed; retry with the same idempotency key.";

  private final DirectoryStore store;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public DirectoryApplicationService(
      @Lazy DirectoryStore store,
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      @Lazy PlatformTransactionManager transactionManager,
      @Qualifier("auditClock") Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter");
    this.transaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public InternalId createOrganization(
      ExecutionContext context,
      String name,
      Optional<InternalId> parentOrganizationId,
      DirectorySource source,
      DirectoryProvenance provenance,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(
        context,
        DirectoryAuthorities.curation().code(),
        "DIRECTORY_ORGANIZATION_CREATE",
        "Organization",
        Optional.empty(),
        Optional.empty());
    if (context.scope().kind() != ExecutionScope.Kind.PLATFORM) {
      denyScope(
          context,
          "DIRECTORY_ORGANIZATION_CREATE",
          "Organization",
          Optional.empty(),
          Optional.empty());
      throw scopeMismatch();
    }
    requireSourceType(source, "Organization");
    CommandReceiptKey receiptKey =
        commandKey(context, "organization.create", "organizations", rawIdempotencyKey);
    return executeIdempotent(
        receiptKey,
        fingerprint,
        201,
        () -> {
          parentOrganizationId.ifPresent(
              parentId -> {
                Organization parent =
                    store
                        .lockOrganization(parentId)
                        .orElseThrow(DirectoryApplicationService::notFound);
                if (parent.status() != DirectoryLifecycleStatus.ACTIVE) {
                  throw inactive();
                }
              });
          InternalId id = InternalId.generate();
          Organization organization =
              new Organization(
                  id,
                  normalizedName(name),
                  parentOrganizationId,
                  source,
                  provenance,
                  DirectoryLifecycleStatus.ACTIVE,
                  1);
          store.insertOrganization(organization);
          auditMutation(
              context,
              "DIRECTORY_ORGANIZATION_CREATE",
              "Organization",
              id,
              provenance.reasonCode(),
              Optional.of(organizationReference(id)),
              Optional.empty());
          return id;
        });
  }

  public Organization getOrganization(ExecutionContext context, InternalId id) {
    requireAuthority(
        context,
        DirectoryAuthorities.read().code(),
        "DIRECTORY_ORGANIZATION_READ",
        "Organization",
        Optional.of(id),
        Optional.empty());
    Organization organization =
        store.findOrganization(id).orElseThrow(DirectoryApplicationService::notFound);
    if (!scopeCanReadOrganization(context.scope(), organization.id())) {
      denyScope(
          context,
          "DIRECTORY_ORGANIZATION_READ",
          "Organization",
          Optional.of(id),
          Optional.of(organizationReference(id)));
      throw scopeMismatch();
    }
    auditRead(
        context,
        "DIRECTORY_ORGANIZATION_READ",
        "Organization",
        id,
        Optional.of(organizationReference(id)),
        Optional.empty());
    return organization;
  }

  public void correctOrganization(
      ExecutionContext context,
      InternalId id,
      long expectedVersion,
      String name,
      DirectoryProvenance provenance,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(
        context,
        DirectoryAuthorities.curation().code(),
        "DIRECTORY_ORGANIZATION_CORRECT",
        "Organization",
        Optional.of(id),
        Optional.empty());
    if (!scopeCanCurateOrganization(context.scope(), id)) {
      denyScope(
          context,
          "DIRECTORY_ORGANIZATION_CORRECT",
          "Organization",
          Optional.of(id),
          Optional.of(organizationReference(id)));
      throw scopeMismatch();
    }
    CommandReceiptKey receiptKey =
        commandKey(context, "organization.correct", "organizations/" + id, rawIdempotencyKey);
    executeIdempotent(
        receiptKey,
        fingerprint,
        204,
        () -> {
          Organization current =
              store.lockOrganization(id).orElseThrow(DirectoryApplicationService::notFound);
          if (current.status() != DirectoryLifecycleStatus.ACTIVE) {
            throw inactive();
          }
          requireExpectedVersion(current.version(), expectedVersion);
          Organization corrected = current.correct(normalizedName(name), provenance);
          store.appendOrganizationRevision(current, provenance.reasonCode());
          if (!store.updateOrganization(corrected, expectedVersion, clock.instant())) {
            throw versionConflict();
          }
          auditMutation(
              context,
              "DIRECTORY_ORGANIZATION_CORRECT",
              "Organization",
              id,
              provenance.reasonCode(),
              Optional.of(organizationReference(id)),
              Optional.empty());
          return id;
        });
  }

  public InternalId createFacility(
      ExecutionContext context,
      InternalId managingOrganizationId,
      String name,
      DirectorySource source,
      DirectoryProvenance provenance,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(
        context,
        DirectoryAuthorities.curation().code(),
        "DIRECTORY_FACILITY_CREATE",
        "Facility",
        Optional.empty(),
        Optional.of(organizationReference(managingOrganizationId)));
    if (!scopeCanCreateFacility(context.scope(), managingOrganizationId)) {
      denyScope(
          context,
          "DIRECTORY_FACILITY_CREATE",
          "Facility",
          Optional.empty(),
          Optional.of(organizationReference(managingOrganizationId)));
      throw scopeMismatch();
    }
    requireSourceType(source, "Facility");
    CommandReceiptKey receiptKey =
        commandKey(context, "facility.create", "facilities", rawIdempotencyKey);
    return executeIdempotent(
        receiptKey,
        fingerprint,
        201,
        () -> {
          Organization organization =
              store
                  .lockOrganization(managingOrganizationId)
                  .orElseThrow(DirectoryApplicationService::notFound);
          if (organization.status() != DirectoryLifecycleStatus.ACTIVE) {
            throw inactive();
          }
          InternalId id = InternalId.generate();
          Facility facility =
              new Facility(
                  id,
                  managingOrganizationId,
                  normalizedName(name),
                  source,
                  provenance,
                  DirectoryLifecycleStatus.ACTIVE,
                  1);
          store.insertFacility(facility);
          auditMutation(
              context,
              "DIRECTORY_FACILITY_CREATE",
              "Facility",
              id,
              provenance.reasonCode(),
              Optional.of(organizationReference(managingOrganizationId)),
              Optional.of(facilityReference(id)));
          return id;
        });
  }

  public InternalId createLocation(
      ExecutionContext context,
      InternalId facilityId,
      String name,
      Optional<Wgs84Coordinate> coordinate,
      boolean primary,
      DirectorySource source,
      DirectoryProvenance provenance,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(
        context,
        DirectoryAuthorities.curation().code(),
        "DIRECTORY_LOCATION_CREATE",
        "Location",
        Optional.empty(),
        Optional.empty());
    Facility owner =
        store.findFacility(facilityId).orElseThrow(DirectoryApplicationService::notFound);
    if (!scopeCanReadFacility(context.scope(), owner)) {
      denyScope(
          context,
          "DIRECTORY_LOCATION_CREATE",
          "Location",
          Optional.empty(),
          Optional.of(organizationReference(owner.managingOrganizationId())),
          Optional.of(facilityReference(facilityId)));
      throw scopeMismatch();
    }
    requireSourceType(source, "Location");
    CommandReceiptKey receiptKey =
        commandKey(
            context,
            "location.create",
            "facilities/" + facilityId + "/locations",
            rawIdempotencyKey);
    return executeIdempotent(
        receiptKey,
        fingerprint,
        201,
        () -> {
          Facility lockedFacility =
              store.lockFacility(facilityId).orElseThrow(DirectoryApplicationService::notFound);
          if (!scopeCanReadFacility(context.scope(), lockedFacility)) {
            denyScope(
                context,
                "DIRECTORY_LOCATION_CREATE",
                "Location",
                Optional.empty(),
                Optional.of(organizationReference(lockedFacility.managingOrganizationId())),
                Optional.of(facilityReference(facilityId)));
            throw scopeMismatch();
          }
          if (lockedFacility.status() != DirectoryLifecycleStatus.ACTIVE) {
            throw inactive();
          }
          Organization organization =
              store
                  .lockOrganization(lockedFacility.managingOrganizationId())
                  .orElseThrow(DirectoryApplicationService::notFound);
          if (organization.status() != DirectoryLifecycleStatus.ACTIVE) {
            throw inactive();
          }
          if (primary) {
            demotePrimaryLocation(facilityId, Optional.empty(), provenance);
          }
          InternalId id = InternalId.generate();
          store.insertLocation(
              new DirectoryLocation(
                  id,
                  facilityId,
                  normalizedName(name),
                  coordinate,
                  primary,
                  source,
                  provenance,
                  1));
          auditMutation(
              context,
              "DIRECTORY_LOCATION_CREATE",
              "Location",
              id,
              provenance.reasonCode(),
              Optional.of(organizationReference(lockedFacility.managingOrganizationId())),
              Optional.of(facilityReference(facilityId)));
          return id;
        });
  }

  public void correctLocation(
      ExecutionContext context,
      InternalId locationId,
      long expectedVersion,
      String name,
      Optional<Wgs84Coordinate> coordinate,
      boolean primary,
      DirectoryProvenance provenance,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(
        context,
        DirectoryAuthorities.curation().code(),
        "DIRECTORY_LOCATION_CORRECT",
        "Location",
        Optional.of(locationId),
        Optional.empty());
    DirectoryLocation reference =
        store.findLocation(locationId).orElseThrow(DirectoryApplicationService::notFound);
    Facility owner =
        store
            .findFacility(reference.facilityId())
            .orElseThrow(DirectoryApplicationService::notFound);
    if (!scopeCanReadFacility(context.scope(), owner)) {
      denyScope(
          context,
          "DIRECTORY_LOCATION_CORRECT",
          "Location",
          Optional.of(locationId),
          Optional.of(organizationReference(owner.managingOrganizationId())),
          Optional.of(facilityReference(owner.id())));
      throw scopeMismatch();
    }
    CommandReceiptKey receiptKey =
        commandKey(context, "location.correct", "locations/" + locationId, rawIdempotencyKey);
    executeIdempotent(
        receiptKey,
        fingerprint,
        204,
        () -> {
          Facility lockedFacility =
              store
                  .lockFacility(reference.facilityId())
                  .orElseThrow(DirectoryApplicationService::notFound);
          if (!scopeCanReadFacility(context.scope(), lockedFacility)) {
            denyScope(
                context,
                "DIRECTORY_LOCATION_CORRECT",
                "Location",
                Optional.of(locationId),
                Optional.of(organizationReference(lockedFacility.managingOrganizationId())),
                Optional.of(facilityReference(lockedFacility.id())));
            throw scopeMismatch();
          }
          if (lockedFacility.status() != DirectoryLifecycleStatus.ACTIVE) {
            throw inactive();
          }
          DirectoryLocation current =
              store.lockLocation(locationId).orElseThrow(DirectoryApplicationService::notFound);
          if (!current.facilityId().equals(lockedFacility.id())) {
            throw DirectoryApplicationService.notFound();
          }
          requireExpectedVersion(current.version(), expectedVersion);
          if (primary) {
            demotePrimaryLocation(lockedFacility.id(), Optional.of(locationId), provenance);
          }
          store.appendLocationRevision(current, provenance.reasonCode());
          DirectoryLocation corrected =
              current.correct(normalizedName(name), coordinate, primary, provenance);
          if (!store.updateLocation(corrected, expectedVersion, clock.instant())) {
            throw versionConflict();
          }
          auditMutation(
              context,
              "DIRECTORY_LOCATION_CORRECT",
              "Location",
              locationId,
              provenance.reasonCode(),
              Optional.of(organizationReference(lockedFacility.managingOrganizationId())),
              Optional.of(facilityReference(lockedFacility.id())));
          return locationId;
        });
  }

  private void demotePrimaryLocation(
      InternalId facilityId, Optional<InternalId> exceptLocation, DirectoryProvenance provenance) {
    for (DirectoryLocation current : store.listLocations(facilityId)) {
      if (current.primary() && !exceptLocation.filter(current.id()::equals).isPresent()) {
        store.appendLocationRevision(current, provenance.reasonCode());
        if (!store.updateLocation(
            current.withPrimary(false, provenance), current.version(), clock.instant())) {
          throw versionConflict();
        }
      }
    }
  }

  public FacilityDirectoryView getFacility(ExecutionContext context, InternalId id) {
    requireAuthority(
        context,
        DirectoryAuthorities.read().code(),
        "DIRECTORY_FACILITY_READ",
        "Facility",
        Optional.of(id),
        Optional.empty());
    Facility facility = store.findFacility(id).orElseThrow(DirectoryApplicationService::notFound);
    if (!scopeCanReadFacility(context.scope(), facility)) {
      denyScope(
          context,
          "DIRECTORY_FACILITY_READ",
          "Facility",
          Optional.of(id),
          Optional.of(organizationReference(facility.managingOrganizationId())),
          Optional.of(facilityReference(id)));
      throw scopeMismatch();
    }
    auditRead(
        context,
        "DIRECTORY_FACILITY_READ",
        "Facility",
        id,
        Optional.of(organizationReference(facility.managingOrganizationId())),
        Optional.of(facilityReference(id)));
    return new FacilityDirectoryView(facility, store.listLocations(facility.id()));
  }

  public NearbyFacilityPage findNearbyFacilities(
      ExecutionContext context,
      Wgs84Coordinate center,
      double radiusMeters,
      Optional<InternalId> requestedOrganizationId,
      int requestedPageSize,
      int offset) {
    requireAuthority(
        context,
        DirectoryAuthorities.read().code(),
        "DIRECTORY_FACILITY_NEARBY_READ",
        "FacilitySearch",
        Optional.empty(),
        requestedOrganizationId.map(DirectoryApplicationService::organizationReference));
    if (!Double.isFinite(radiusMeters)
        || radiusMeters <= 0.0
        || radiusMeters > MAX_SEARCH_RADIUS_METERS) {
      throw coordinateInvalid("Radius must be greater than zero and no more than 500000 meters.");
    }
    int pageSize = requestedPageSize == 0 ? DEFAULT_SEARCH_PAGE_SIZE : requestedPageSize;
    if (pageSize < 1
        || pageSize > MAX_SEARCH_PAGE_SIZE
        || offset < 0
        || offset > MAX_SEARCH_OFFSET) {
      throw validation("Search page size must be between 1 and 100 and offset at most 10000.");
    }
    SearchScope searchScope = resolveSearchScope(context, requestedOrganizationId);
    auditSearchRead(context, searchScope.organizationId());
    List<LocatedFacilitySearchResult> nearby =
        store.findNearbyFacilities(
            searchScope.organizationId(),
            searchScope.facilityId(),
            center,
            radiusMeters,
            pageSize + 1,
            offset);
    List<UnlocatedFacilitySearchResult> unlocated =
        store.findUnlocatedFacilities(
            searchScope.organizationId(), searchScope.facilityId(), pageSize + 1, offset);
    boolean hasMoreNearby = nearby.size() > pageSize;
    boolean hasMoreUnlocated = unlocated.size() > pageSize;
    return new NearbyFacilityPage(
        nearby.stream().limit(pageSize).toList(),
        unlocated.stream().limit(pageSize).toList(),
        pageSize,
        offset,
        hasMoreNearby,
        hasMoreUnlocated);
  }

  private SearchScope resolveSearchScope(
      ExecutionContext context, Optional<InternalId> requestedOrganizationId) {
    return switch (context.scope().kind()) {
      case PLATFORM -> new SearchScope(requestedOrganizationId, Optional.empty());
      case ORGANIZATION -> {
        InternalId scopeOrganizationId =
            referenceId(context.scope().organization().orElseThrow(), "Organization");
        if (requestedOrganizationId.isPresent()
            && !requestedOrganizationId.orElseThrow().equals(scopeOrganizationId)) {
          denyScope(
              context,
              "DIRECTORY_FACILITY_NEARBY_READ",
              "FacilitySearch",
              Optional.empty(),
              requestedOrganizationId.map(DirectoryApplicationService::organizationReference));
          throw scopeMismatch();
        }
        yield new SearchScope(Optional.of(scopeOrganizationId), Optional.empty());
      }
      case FACILITY -> {
        InternalId scopeOrganizationId =
            referenceId(context.scope().organization().orElseThrow(), "Organization");
        if (requestedOrganizationId.isPresent()
            && !requestedOrganizationId.orElseThrow().equals(scopeOrganizationId)) {
          denyScope(
              context,
              "DIRECTORY_FACILITY_NEARBY_READ",
              "FacilitySearch",
              Optional.empty(),
              requestedOrganizationId.map(DirectoryApplicationService::organizationReference));
          throw scopeMismatch();
        }
        yield new SearchScope(
            Optional.of(scopeOrganizationId),
            Optional.of(referenceId(context.scope().facility().orElseThrow(), "Facility")));
      }
    };
  }

  private static InternalId referenceId(ExternalReference reference, String expectedType) {
    if (!DIRECTORY_ISSUER.equals(reference.issuer().value())
        || !expectedType.equals(reference.resourceType())) {
      throw new IllegalStateException("validated Directory scope has an unexpected reference");
    }
    return InternalId.parse(reference.value());
  }

  private void auditSearchRead(ExecutionContext context, Optional<InternalId> organizationId) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              evidence(
                  context,
                  AuditActionKind.SENSITIVE_READ,
                  "DIRECTORY_FACILITY_NEARBY_READ",
                  "FacilitySearch",
                  Optional.empty(),
                  AuditOutcome.SUCCEEDED,
                  Optional.empty(),
                  AuditAuthorizationOutcome.ALLOWED,
                  organizationId.map(DirectoryApplicationService::organizationReference),
                  context.scope().kind() == ExecutionScope.Kind.FACILITY
                      ? context.scope().facility()
                      : Optional.empty()));
    } catch (AuditPersistenceUnavailableException failure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The requested Directory results are not disclosed because access evidence is unavailable.");
    }
  }

  private record SearchScope(
      Optional<InternalId> organizationId, Optional<InternalId> facilityId) {}

  public record NearbyFacilityPage(
      List<LocatedFacilitySearchResult> nearby,
      List<UnlocatedFacilitySearchResult> unlocated,
      int pageSize,
      int offset,
      boolean hasMoreNearby,
      boolean hasMoreUnlocated) {
    public NearbyFacilityPage {
      nearby = List.copyOf(nearby);
      unlocated = List.copyOf(unlocated);
    }
  }

  public void correctFacility(
      ExecutionContext context,
      InternalId id,
      long expectedVersion,
      String name,
      DirectoryProvenance provenance,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(
        context,
        DirectoryAuthorities.curation().code(),
        "DIRECTORY_FACILITY_CORRECT",
        "Facility",
        Optional.of(id),
        Optional.empty());
    if (!scopeCanCurateFacility(context.scope(), id)) {
      denyScope(
          context, "DIRECTORY_FACILITY_CORRECT", "Facility", Optional.of(id), Optional.empty());
      throw scopeMismatch();
    }
    CommandReceiptKey receiptKey =
        commandKey(context, "facility.correct", "facilities/" + id, rawIdempotencyKey);
    executeIdempotent(
        receiptKey,
        fingerprint,
        204,
        () -> {
          Facility current =
              store.lockFacility(id).orElseThrow(DirectoryApplicationService::notFound);
          if (!scopeCanCurateFacility(context.scope(), id)) {
            denyScope(
                context,
                "DIRECTORY_FACILITY_CORRECT",
                "Facility",
                Optional.of(id),
                Optional.of(organizationReference(current.managingOrganizationId())),
                Optional.of(facilityReference(id)));
            throw scopeMismatch();
          }
          if (current.status() != DirectoryLifecycleStatus.ACTIVE) {
            throw inactive();
          }
          requireExpectedVersion(current.version(), expectedVersion);
          Facility corrected = current.correct(normalizedName(name), provenance);
          store.appendFacilityRevision(current, provenance.reasonCode());
          if (!store.updateFacility(corrected, expectedVersion, clock.instant())) {
            throw versionConflict();
          }
          auditMutation(
              context,
              "DIRECTORY_FACILITY_CORRECT",
              "Facility",
              id,
              provenance.reasonCode(),
              Optional.of(organizationReference(current.managingOrganizationId())),
              Optional.of(facilityReference(id)));
          return id;
        });
  }

  public void deactivateFacility(
      ExecutionContext context,
      InternalId id,
      long expectedVersion,
      DirectoryProvenance provenance,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(
        context,
        DirectoryAuthorities.curation().code(),
        "DIRECTORY_FACILITY_DEACTIVATE",
        "Facility",
        Optional.of(id),
        Optional.empty());
    if (!scopeCanCurateFacility(context.scope(), id)) {
      denyScope(
          context, "DIRECTORY_FACILITY_DEACTIVATE", "Facility", Optional.of(id), Optional.empty());
      throw scopeMismatch();
    }
    CommandReceiptKey receiptKey =
        commandKey(context, "facility.deactivate", "facilities/" + id, rawIdempotencyKey);
    executeIdempotent(
        receiptKey,
        fingerprint,
        204,
        () -> {
          Facility current =
              store.lockFacility(id).orElseThrow(DirectoryApplicationService::notFound);
          if (!scopeCanCurateFacility(context.scope(), id)) {
            denyScope(
                context,
                "DIRECTORY_FACILITY_DEACTIVATE",
                "Facility",
                Optional.of(id),
                Optional.of(organizationReference(current.managingOrganizationId())),
                Optional.of(facilityReference(id)));
            throw scopeMismatch();
          }
          if (current.status() != DirectoryLifecycleStatus.ACTIVE) {
            throw inactive();
          }
          requireExpectedVersion(current.version(), expectedVersion);
          Facility deactivated = current.deactivate(provenance);
          store.appendFacilityRevision(current, provenance.reasonCode());
          if (!store.updateFacility(deactivated, expectedVersion, clock.instant())) {
            throw versionConflict();
          }
          auditMutation(
              context,
              "DIRECTORY_FACILITY_DEACTIVATE",
              "Facility",
              id,
              provenance.reasonCode(),
              Optional.of(organizationReference(current.managingOrganizationId())),
              Optional.of(facilityReference(id)));
          return id;
        });
  }

  /** Durable denial evidence for a request whose proposed scope could not be validated. */
  public void recordInvalidExecutionScope(
      ActorReference actor, CorrelationId correlationId, String actionCode) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              new AuditEvidence(
                  AuditActionKind.SECURITY_OUTCOME,
                  Optional.of(actor),
                  Optional.empty(),
                  actionCode,
                  "DirectoryScope",
                  Optional.empty(),
                  clock.instant(),
                  AuditOutcome.DENIED,
                  Optional.of("INVALID_EXECUTION_SCOPE"),
                  authenticationEvidence(actor.kind()),
                  AuditAuthorizationOutcome.DENIED,
                  correlationId));
    } catch (AuditPersistenceUnavailableException failure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The request remains denied; its required audit record is unavailable.");
    }
  }

  private InternalId executeIdempotent(
      CommandReceiptKey key,
      RequestFingerprint fingerprint,
      int responseStatus,
      Supplier<InternalId> command) {
    try {
      return transaction.execute(
          status -> {
            store.configureBoundedReceiptWait();
            DirectoryStore.ReceiptClaim claim =
                store.claimReceipt(key, fingerprint, clock.instant());
            return switch (claim.state()) {
              case KEY_REUSED ->
                  throw problem(
                      HttpStatus.CONFLICT,
                      PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
                      "The idempotency key was already used for a different Directory command.");
              case IN_PROGRESS ->
                  throw problem(
                      HttpStatus.CONFLICT,
                      PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS,
                      COMMAND_BUSY_DETAIL);
              case REPLAY -> claim.result().orElseThrow();
              case OWNER -> {
                InternalId result = command.get();
                store.completeReceipt(key, responseStatus, result, clock.instant());
                yield result;
              }
            };
          });
    } catch (CannotAcquireLockException failure) {
      throw problem(
          HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, COMMAND_BUSY_DETAIL);
    } catch (AuditPersistenceUnavailableException failure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The Directory change could not be committed with its required audit evidence.");
    } catch (DataAccessException failure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.REQUEST_FAILED,
          "The Directory command could not be completed safely.");
    }
  }

  private void requireAuthority(
      ExecutionContext context,
      String authorityCode,
      String actionCode,
      String targetType,
      Optional<InternalId> targetId,
      Optional<ExternalReference> targetOrganization) {
    AuthorizationDecision decision = authorization.getObject().evaluate(context, authorityCode);
    if (decision.isAllowed()) {
      return;
    }
    recordDenial(
        context,
        actionCode,
        targetType,
        targetId,
        targetOrganization,
        Optional.empty(),
        decision.denialReason().map(Enum::name).orElse("AUTHORIZATION_DENIED"));
    if (decision.denialReason().orElse(null)
        == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
          "Authorization could not be evaluated safely.");
    }
    throw problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.AUTHORIZATION_DENIED,
        "The authenticated principal is not authorized for this Directory operation.");
  }

  private void auditMutation(
      ExecutionContext context,
      String actionCode,
      String targetType,
      InternalId targetId,
      String reasonCode,
      Optional<ExternalReference> organization,
      Optional<ExternalReference> facility) {
    try {
      auditWriter
          .getObject()
          .recordMutation(
              evidence(
                  context,
                  AuditActionKind.PROTECTED_MUTATION,
                  actionCode,
                  targetType,
                  Optional.of(targetId),
                  AuditOutcome.SUCCEEDED,
                  Optional.of(reasonCode),
                  AuditAuthorizationOutcome.ALLOWED,
                  organization,
                  facility));
    } catch (AuditPersistenceUnavailableException failure) {
      throw failure;
    }
  }

  private void auditRead(
      ExecutionContext context,
      String actionCode,
      String targetType,
      InternalId targetId,
      Optional<ExternalReference> organization,
      Optional<ExternalReference> facility) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              evidence(
                  context,
                  AuditActionKind.SENSITIVE_READ,
                  actionCode,
                  targetType,
                  Optional.of(targetId),
                  AuditOutcome.SUCCEEDED,
                  Optional.empty(),
                  AuditAuthorizationOutcome.ALLOWED,
                  organization,
                  facility));
    } catch (AuditPersistenceUnavailableException failure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The requested Directory record is not disclosed because access evidence is unavailable.");
    }
  }

  private void denyScope(
      ExecutionContext context,
      String actionCode,
      String targetType,
      Optional<InternalId> targetId,
      Optional<ExternalReference> organization) {
    denyScope(context, actionCode, targetType, targetId, organization, Optional.empty());
  }

  private void denyScope(
      ExecutionContext context,
      String actionCode,
      String targetType,
      Optional<InternalId> targetId,
      Optional<ExternalReference> organization,
      Optional<ExternalReference> facility) {
    recordDenial(
        context,
        actionCode,
        targetType,
        targetId,
        organization,
        facility,
        "DIRECTORY_SCOPE_MISMATCH");
  }

  private void recordDenial(
      ExecutionContext context,
      String actionCode,
      String targetType,
      Optional<InternalId> targetId,
      Optional<ExternalReference> organization,
      Optional<ExternalReference> facility,
      String reasonCode) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              evidence(
                  context,
                  AuditActionKind.SECURITY_OUTCOME,
                  actionCode,
                  targetType,
                  targetId,
                  AuditOutcome.DENIED,
                  Optional.of(reasonCode),
                  AuditAuthorizationOutcome.DENIED,
                  organization,
                  facility));
    } catch (AuditPersistenceUnavailableException failure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The denial remains in effect; its required audit record is unavailable.");
    }
  }

  private AuditEvidence evidence(
      ExecutionContext context,
      AuditActionKind kind,
      String actionCode,
      String targetType,
      Optional<InternalId> targetId,
      AuditOutcome outcome,
      Optional<String> reasonCode,
      AuditAuthorizationOutcome authorizationOutcome,
      Optional<ExternalReference> organization,
      Optional<ExternalReference> facility) {
    Optional<AuditTargetContext> targetContext =
        organization.isPresent() || facility.isPresent()
            ? Optional.of(
                new AuditTargetContext(
                    facility.isPresent()
                        ? ExecutionScope.Kind.FACILITY
                        : ExecutionScope.Kind.ORGANIZATION,
                    organization,
                    facility))
            : Optional.empty();
    return new AuditEvidence(
        kind,
        Optional.of(context.actor()),
        Optional.of(context.scope()),
        actionCode,
        targetType,
        targetId,
        clock.instant(),
        outcome,
        reasonCode,
        authenticationEvidence(context.authenticationMechanism()),
        authorizationOutcome,
        context.correlationId(),
        targetContext);
  }

  private CommandReceiptKey commandKey(
      ExecutionContext context, String operation, String target, String rawKey) {
    try {
      return CommandReceiptKey.from(context, operation, target, rawKey);
    } catch (IllegalArgumentException failure) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.VALIDATION_FAILED,
          "A valid Idempotency-Key is required for this Directory command.");
    }
  }

  private boolean scopeCanReadOrganization(ExecutionScope scope, InternalId organizationId) {
    return switch (scope.kind()) {
      case PLATFORM -> true;
      case ORGANIZATION ->
          scope.organization().orElseThrow().equals(organizationReference(organizationId));
      case FACILITY -> false;
    };
  }

  private boolean scopeCanCurateOrganization(ExecutionScope scope, InternalId organizationId) {
    return scopeCanReadOrganization(scope, organizationId);
  }

  private boolean scopeCanCreateFacility(ExecutionScope scope, InternalId organizationId) {
    return switch (scope.kind()) {
      case PLATFORM -> true;
      case ORGANIZATION ->
          scope.organization().orElseThrow().equals(organizationReference(organizationId));
      case FACILITY -> false;
    };
  }

  private boolean scopeCanReadFacility(ExecutionScope scope, Facility facility) {
    return switch (scope.kind()) {
      case PLATFORM -> true;
      case ORGANIZATION ->
          scope
              .organization()
              .orElseThrow()
              .equals(organizationReference(facility.managingOrganizationId()));
      case FACILITY ->
          scope
                  .organization()
                  .orElseThrow()
                  .equals(organizationReference(facility.managingOrganizationId()))
              && scope.facility().orElseThrow().equals(facilityReference(facility.id()));
    };
  }

  private boolean scopeCanCurateFacility(ExecutionScope scope, InternalId facilityId) {
    return switch (scope.kind()) {
      case PLATFORM -> true;
      case ORGANIZATION ->
          store
              .findFacility(facilityId)
              .map(
                  facility ->
                      scope
                          .organization()
                          .orElseThrow()
                          .equals(organizationReference(facility.managingOrganizationId())))
              .orElse(false);
      case FACILITY -> scope.facility().orElseThrow().equals(facilityReference(facilityId));
    };
  }

  private static void requireSourceType(DirectorySource source, String expectedType) {
    if (source == null || !expectedType.equals(source.reference().resourceType())) {
      throw validation("The source resource type must match the Directory record type.");
    }
  }

  private static String normalizedName(String name) {
    if (name == null || name.isBlank() || !name.equals(name.strip()) || name.length() > 200) {
      throw validation("The Directory name must be trimmed and between 1 and 200 characters.");
    }
    return name;
  }

  private static void requireExpectedVersion(long actual, long expected) {
    if (expected < 1 || actual != expected) {
      throw versionConflict();
    }
  }

  private static ExternalReference organizationReference(InternalId id) {
    return new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Organization", id.toString());
  }

  private static ExternalReference facilityReference(InternalId id) {
    return new ExternalReference(new IssuerId(DIRECTORY_ISSUER), "Facility", id.toString());
  }

  private static DirectoryProvenance provenance(
      String method,
      Instant observedAt,
      String reasonCode,
      ExecutionContext context,
      Instant recordedAt) {
    return new DirectoryProvenance(method, observedAt, context.actor(), recordedAt, reasonCode);
  }

  private static AuditAuthenticationEvidence authenticationEvidence(
      AuthenticationMechanism mechanism) {
    return switch (mechanism) {
      case HUMAN_ACCESS_TOKEN -> AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN;
      case SERVICE_CLIENT_CREDENTIALS -> AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
      case CONFIGURED_SERVICE_IDENTITY -> AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY;
      case LOCAL_OPERATOR_CONTROL -> AuditAuthenticationEvidence.LOCAL_OPERATOR_CONTROL;
    };
  }

  private static AuditAuthenticationEvidence authenticationEvidence(ActorKind kind) {
    return kind == ActorKind.HUMAN
        ? AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN
        : AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
  }

  private static PlatformProblemException notFound() {
    return problem(
        HttpStatus.NOT_FOUND,
        PlatformErrorCode.DIRECTORY_NOT_FOUND,
        "The Directory record was not found.");
  }

  private static PlatformProblemException inactive() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.DIRECTORY_INACTIVE,
        "The inactive Directory record cannot be used for this operation.");
  }

  private static PlatformProblemException versionConflict() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.DIRECTORY_VERSION_CONFLICT,
        "The Directory record changed; retrieve the current version and retry.");
  }

  private static PlatformProblemException scopeMismatch() {
    return problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.DIRECTORY_SCOPE_MISMATCH,
        "The operation is outside the validated Directory scope.");
  }

  private static PlatformProblemException validation(String detail) {
    return problem(HttpStatus.BAD_REQUEST, PlatformErrorCode.DIRECTORY_VALIDATION_FAILED, detail);
  }

  private static PlatformProblemException coordinateInvalid(String detail) {
    return problem(HttpStatus.BAD_REQUEST, PlatformErrorCode.DIRECTORY_COORDINATE_INVALID, detail);
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
