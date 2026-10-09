package org.ayuvahak.directory.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.ayuvahak.directory.domain.CapabilityVerificationStatus;
import org.ayuvahak.directory.domain.CareCapability;
import org.ayuvahak.directory.domain.DirectoryAuthorities;
import org.ayuvahak.directory.domain.DirectoryConceptType;
import org.ayuvahak.directory.domain.DirectoryExternalIdentifier;
import org.ayuvahak.directory.domain.DirectoryLifecycleStatus;
import org.ayuvahak.directory.domain.DirectoryLocation;
import org.ayuvahak.directory.domain.DirectoryProvenance;
import org.ayuvahak.directory.domain.DirectorySource;
import org.ayuvahak.directory.domain.ExternalIdentifierStatus;
import org.ayuvahak.directory.domain.Facility;
import org.ayuvahak.directory.domain.FacilityCapabilityAssertion;
import org.ayuvahak.directory.domain.HealthcareService;
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
  private static final int DEFAULT_PAGE_SIZE = 50;
  private static final int MAX_PAGE_SIZE = 100;
  private static final int MAX_PAGE_OFFSET = 10_000;
  private static final double MAX_SEARCH_RADIUS_METERS = 500_000.0;
  private static final int MAX_REQUIRED_CAPABILITIES = 20;
  private static final Pattern CAPABILITY_CODE = Pattern.compile("[A-Z][A-Z0-9._-]{0,63}");
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

  public InternalId addExternalIdentifier(
      ExecutionContext context,
      DirectoryConceptType conceptType,
      InternalId conceptId,
      ExternalReference externalIdentifier,
      DirectoryProvenance provenance,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    String actionCode = "DIRECTORY_EXTERNAL_IDENTIFIER_ADD";
    requireAuthority(
        context,
        DirectoryAuthorities.curation().code(),
        actionCode,
        "ExternalIdentifier",
        Optional.of(conceptId),
        Optional.empty());
    CommandReceiptKey receiptKey =
        commandKey(
            context,
            "external-identifier.add",
            conceptType.name() + "/" + conceptId,
            rawIdempotencyKey);
    try {
      return executeIdempotent(
          receiptKey,
          fingerprint,
          201,
          () -> {
            DirectoryConceptOwner owner =
                lockActiveConceptOwner(context, conceptType, conceptId, actionCode);
            InternalId id = InternalId.generate();
            Instant now = clock.instant();
            DirectoryExternalIdentifier identifier =
                new DirectoryExternalIdentifier(
                    id,
                    conceptType,
                    conceptId,
                    externalIdentifier,
                    ExternalIdentifierStatus.ACTIVE,
                    Optional.empty(),
                    provenance,
                    1,
                    now,
                    now);
            store.insertExternalIdentifier(identifier);
            auditMutation(
                context,
                actionCode,
                "ExternalIdentifier",
                id,
                provenance.reasonCode(),
                owner.organizationId().map(DirectoryApplicationService::organizationReference),
                owner.facilityId().map(DirectoryApplicationService::facilityReference));
            return id;
          });
    } catch (ActiveExternalIdentifierConflictException conflict) {
      throw identifierConflict();
    }
  }

  public void removeExternalIdentifier(
      ExecutionContext context,
      InternalId identifierId,
      long expectedVersion,
      DirectoryProvenance provenance,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    String actionCode = "DIRECTORY_EXTERNAL_IDENTIFIER_REMOVE";
    requireAuthority(
        context,
        DirectoryAuthorities.curation().code(),
        actionCode,
        "ExternalIdentifier",
        Optional.of(identifierId),
        Optional.empty());
    CommandReceiptKey receiptKey =
        commandKey(
            context,
            "external-identifier.remove",
            "external-identifiers/" + identifierId,
            rawIdempotencyKey);
    executeIdempotent(
        receiptKey,
        fingerprint,
        204,
        () -> {
          DirectoryExternalIdentifier current =
              store
                  .lockExternalIdentifier(identifierId)
                  .orElseThrow(DirectoryApplicationService::notFound);
          DirectoryConceptOwner owner =
              lockConceptOwner(context, current.conceptType(), current.conceptId(), actionCode);
          if (current.status() != ExternalIdentifierStatus.ACTIVE) {
            throw versionConflict();
          }
          requireExpectedVersion(current.version(), expectedVersion);
          Instant changedAt = clock.instant();
          DirectoryExternalIdentifier removed =
              current.transition(ExternalIdentifierStatus.REMOVED, provenance, changedAt);
          store.appendExternalIdentifierRevision(current, provenance.reasonCode(), changedAt);
          if (!store.updateExternalIdentifier(removed, expectedVersion, changedAt)) {
            throw versionConflict();
          }
          auditMutation(
              context,
              actionCode,
              "ExternalIdentifier",
              identifierId,
              provenance.reasonCode(),
              owner.organizationId().map(DirectoryApplicationService::organizationReference),
              owner.facilityId().map(DirectoryApplicationService::facilityReference));
          return identifierId;
        });
  }

  public InternalId supersedeExternalIdentifier(
      ExecutionContext context,
      InternalId identifierId,
      long expectedVersion,
      ExternalReference replacement,
      DirectoryProvenance provenance,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    String actionCode = "DIRECTORY_EXTERNAL_IDENTIFIER_SUPERSEDE";
    requireAuthority(
        context,
        DirectoryAuthorities.curation().code(),
        actionCode,
        "ExternalIdentifier",
        Optional.of(identifierId),
        Optional.empty());
    CommandReceiptKey receiptKey =
        commandKey(
            context,
            "external-identifier.supersede",
            "external-identifiers/" + identifierId,
            rawIdempotencyKey);
    try {
      return executeIdempotent(
          receiptKey,
          fingerprint,
          201,
          () -> {
            DirectoryExternalIdentifier current =
                store
                    .lockExternalIdentifier(identifierId)
                    .orElseThrow(DirectoryApplicationService::notFound);
            DirectoryConceptOwner owner =
                lockActiveConceptOwner(
                    context, current.conceptType(), current.conceptId(), actionCode);
            if (current.status() != ExternalIdentifierStatus.ACTIVE) {
              throw versionConflict();
            }
            requireExpectedVersion(current.version(), expectedVersion);
            if (current.externalIdentifier().equals(replacement)) {
              throw validation(
                  "A superseding identifier must have a different issuer, type, or value.");
            }
            Instant changedAt = clock.instant();
            DirectoryExternalIdentifier superseded =
                current.transition(ExternalIdentifierStatus.SUPERSEDED, provenance, changedAt);
            store.appendExternalIdentifierRevision(current, provenance.reasonCode(), changedAt);
            if (!store.updateExternalIdentifier(superseded, expectedVersion, changedAt)) {
              throw versionConflict();
            }
            InternalId replacementId = InternalId.generate();
            DirectoryExternalIdentifier successor =
                new DirectoryExternalIdentifier(
                    replacementId,
                    current.conceptType(),
                    current.conceptId(),
                    replacement,
                    ExternalIdentifierStatus.ACTIVE,
                    Optional.of(current.id()),
                    provenance,
                    1,
                    changedAt,
                    changedAt);
            store.insertExternalIdentifier(successor);
            auditMutation(
                context,
                actionCode,
                "ExternalIdentifier",
                identifierId,
                provenance.reasonCode(),
                owner.organizationId().map(DirectoryApplicationService::organizationReference),
                owner.facilityId().map(DirectoryApplicationService::facilityReference));
            return replacementId;
          });
    } catch (ActiveExternalIdentifierConflictException conflict) {
      throw identifierConflict();
    }
  }

  public DirectoryExternalIdentifierPage listExternalIdentifiers(
      ExecutionContext context,
      DirectoryConceptType conceptType,
      InternalId conceptId,
      boolean includeHistory,
      Integer requestedPage,
      Integer requestedSize) {
    String actionCode = "DIRECTORY_EXTERNAL_IDENTIFIER_READ";
    requireAuthority(
        context,
        DirectoryAuthorities.read().code(),
        actionCode,
        "ExternalIdentifierCollection",
        Optional.of(conceptId),
        Optional.empty());
    DirectoryConceptOwner owner =
        store
            .findConceptOwner(conceptType, conceptId)
            .orElseThrow(DirectoryApplicationService::notFound);
    requireConceptReadScope(context, conceptType, conceptId, owner, actionCode);
    PageBounds page = validatedPageBounds(requestedPage, requestedSize);
    auditRead(
        context,
        actionCode,
        "ExternalIdentifierCollection",
        conceptId,
        owner.organizationId().map(DirectoryApplicationService::organizationReference),
        owner.facilityId().map(DirectoryApplicationService::facilityReference));
    List<DirectoryExternalIdentifierHistoryEntry> entries =
        store.listExternalIdentifiers(
            conceptType, conceptId, includeHistory, page.size() + 1, page.offset());
    return new DirectoryExternalIdentifierPage(
        entries.stream().limit(page.size()).toList(),
        page.page(),
        page.size(),
        entries.size() > page.size());
  }

  private DirectoryConceptOwner lockActiveConceptOwner(
      ExecutionContext context,
      DirectoryConceptType conceptType,
      InternalId conceptId,
      String actionCode) {
    DirectoryConceptOwner owner = lockConceptOwner(context, conceptType, conceptId, actionCode);
    if (!owner.active()) {
      throw inactive();
    }
    return owner;
  }

  private DirectoryConceptOwner lockConceptOwner(
      ExecutionContext context,
      DirectoryConceptType conceptType,
      InternalId conceptId,
      String actionCode) {
    DirectoryConceptOwner owner =
        store
            .lockConceptOwner(conceptType, conceptId)
            .orElseThrow(DirectoryApplicationService::notFound);
    requireConceptCurationScope(context, conceptType, conceptId, owner, actionCode);
    return owner;
  }

  private void requireConceptCurationScope(
      ExecutionContext context,
      DirectoryConceptType conceptType,
      InternalId conceptId,
      DirectoryConceptOwner owner,
      String actionCode) {
    if (!scopeAllowsConcept(context.scope(), owner, false)) {
      denyScope(
          context,
          actionCode,
          "DirectoryConcept",
          Optional.of(conceptId),
          owner.organizationId().map(DirectoryApplicationService::organizationReference),
          owner.facilityId().map(DirectoryApplicationService::facilityReference));
      throw scopeMismatch();
    }
  }

  private void requireConceptReadScope(
      ExecutionContext context,
      DirectoryConceptType conceptType,
      InternalId conceptId,
      DirectoryConceptOwner owner,
      String actionCode) {
    if (!scopeAllowsConcept(context.scope(), owner, true)) {
      denyScope(
          context,
          actionCode,
          "DirectoryConcept",
          Optional.of(conceptId),
          owner.organizationId().map(DirectoryApplicationService::organizationReference),
          owner.facilityId().map(DirectoryApplicationService::facilityReference));
      throw scopeMismatch();
    }
  }

  private static boolean scopeAllowsConcept(
      ExecutionScope scope, DirectoryConceptOwner owner, boolean allowGlobalConceptRead) {
    if (owner.facilityId().isPresent()) {
      return switch (scope.kind()) {
        case PLATFORM -> true;
        case ORGANIZATION ->
            scope
                .organization()
                .orElseThrow()
                .equals(organizationReference(owner.organizationId().orElseThrow()));
        case FACILITY ->
            scope
                    .organization()
                    .orElseThrow()
                    .equals(organizationReference(owner.organizationId().orElseThrow()))
                && scope
                    .facility()
                    .orElseThrow()
                    .equals(facilityReference(owner.facilityId().orElseThrow()));
      };
    }
    if (owner.organizationId().isPresent()) {
      return switch (scope.kind()) {
        case PLATFORM -> true;
        case ORGANIZATION ->
            scope
                .organization()
                .orElseThrow()
                .equals(organizationReference(owner.organizationId().orElseThrow()));
        case FACILITY -> false;
      };
    }
    return allowGlobalConceptRead || scope.kind() == ExecutionScope.Kind.PLATFORM;
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

  public InternalId createHealthcareService(
      ExecutionContext context,
      InternalId organizationId,
      InternalId facilityId,
      Optional<InternalId> locationId,
      String name,
      DirectorySource source,
      DirectoryProvenance provenance,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(
        context,
        DirectoryAuthorities.curation().code(),
        "DIRECTORY_HEALTHCARE_SERVICE_CREATE",
        "HealthcareService",
        Optional.empty(),
        Optional.of(organizationReference(organizationId)));
    Facility facility =
        store.findFacility(facilityId).orElseThrow(DirectoryApplicationService::notFound);
    requireFacilityCurationScope(
        context, "DIRECTORY_HEALTHCARE_SERVICE_CREATE", "HealthcareService", facility);
    if (!organizationId.equals(facility.managingOrganizationId())) {
      denyScope(
          context,
          "DIRECTORY_HEALTHCARE_SERVICE_CREATE",
          "HealthcareService",
          Optional.empty(),
          Optional.of(organizationReference(organizationId)),
          Optional.of(facilityReference(facilityId)));
      throw scopeMismatch();
    }
    requireSourceType(source, "HealthcareService");
    CommandReceiptKey receiptKey =
        commandKey(
            context,
            "healthcare-service.create",
            "facilities/" + facilityId + "/healthcare-services",
            rawIdempotencyKey);
    return executeIdempotent(
        receiptKey,
        fingerprint,
        201,
        () -> {
          Facility currentFacility =
              store.lockFacility(facilityId).orElseThrow(DirectoryApplicationService::notFound);
          requireActiveFacilityOwner(currentFacility);
          requireFacilityCurationScope(
              context, "DIRECTORY_HEALTHCARE_SERVICE_CREATE", "HealthcareService", currentFacility);
          if (!organizationId.equals(currentFacility.managingOrganizationId())) {
            throw scopeMismatch();
          }
          validateLocationOwner(locationId, facilityId);
          InternalId id = InternalId.generate();
          HealthcareService service =
              new HealthcareService(
                  id,
                  organizationId,
                  facilityId,
                  locationId,
                  normalizedName(name),
                  source,
                  provenance,
                  DirectoryLifecycleStatus.ACTIVE,
                  1);
          store.insertHealthcareService(service);
          auditMutation(
              context,
              "DIRECTORY_HEALTHCARE_SERVICE_CREATE",
              "HealthcareService",
              id,
              provenance.reasonCode(),
              Optional.of(organizationReference(organizationId)),
              Optional.of(facilityReference(facilityId)));
          return id;
        });
  }

  public HealthcareService getHealthcareService(ExecutionContext context, InternalId id) {
    requireAuthority(
        context,
        DirectoryAuthorities.read().code(),
        "DIRECTORY_HEALTHCARE_SERVICE_READ",
        "HealthcareService",
        Optional.of(id),
        Optional.empty());
    HealthcareService service =
        store.findHealthcareService(id).orElseThrow(DirectoryApplicationService::notFound);
    Facility facility =
        store.findFacility(service.facilityId()).orElseThrow(DirectoryApplicationService::notFound);
    requireFacilityReadScope(
        context, "DIRECTORY_HEALTHCARE_SERVICE_READ", "HealthcareService", id, facility);
    auditRead(
        context,
        "DIRECTORY_HEALTHCARE_SERVICE_READ",
        "HealthcareService",
        id,
        Optional.of(organizationReference(service.organizationId())),
        Optional.of(facilityReference(service.facilityId())));
    return service;
  }

  public void correctHealthcareService(
      ExecutionContext context,
      InternalId id,
      long expectedVersion,
      String name,
      Optional<InternalId> locationId,
      DirectoryProvenance provenance,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    HealthcareService reference =
        store.findHealthcareService(id).orElseThrow(DirectoryApplicationService::notFound);
    requireAuthority(
        context,
        DirectoryAuthorities.curation().code(),
        "DIRECTORY_HEALTHCARE_SERVICE_CORRECT",
        "HealthcareService",
        Optional.of(id),
        Optional.of(organizationReference(reference.organizationId())));
    Facility facility =
        store
            .findFacility(reference.facilityId())
            .orElseThrow(DirectoryApplicationService::notFound);
    requireFacilityCurationScope(
        context, "DIRECTORY_HEALTHCARE_SERVICE_CORRECT", "HealthcareService", facility);
    CommandReceiptKey receiptKey =
        commandKey(
            context, "healthcare-service.correct", "healthcare-services/" + id, rawIdempotencyKey);
    executeIdempotent(
        receiptKey,
        fingerprint,
        204,
        () -> {
          HealthcareService current =
              store.lockHealthcareService(id).orElseThrow(DirectoryApplicationService::notFound);
          Facility currentFacility =
              store
                  .lockFacility(current.facilityId())
                  .orElseThrow(DirectoryApplicationService::notFound);
          requireFacilityCurationScope(
              context,
              "DIRECTORY_HEALTHCARE_SERVICE_CORRECT",
              "HealthcareService",
              currentFacility);
          if (current.status() != DirectoryLifecycleStatus.ACTIVE) {
            throw inactive();
          }
          requireExpectedVersion(current.version(), expectedVersion);
          validateLocationOwner(locationId, current.facilityId());
          HealthcareService corrected =
              current.correct(normalizedName(name), locationId, provenance);
          store.appendHealthcareServiceRevision(current, provenance.reasonCode());
          if (!store.updateHealthcareService(corrected, expectedVersion, clock.instant())) {
            throw versionConflict();
          }
          auditMutation(
              context,
              "DIRECTORY_HEALTHCARE_SERVICE_CORRECT",
              "HealthcareService",
              id,
              provenance.reasonCode(),
              Optional.of(organizationReference(current.organizationId())),
              Optional.of(facilityReference(current.facilityId())));
          return id;
        });
  }

  public void deactivateHealthcareService(
      ExecutionContext context,
      InternalId id,
      long expectedVersion,
      DirectoryProvenance provenance,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    HealthcareService reference =
        store.findHealthcareService(id).orElseThrow(DirectoryApplicationService::notFound);
    requireAuthority(
        context,
        DirectoryAuthorities.curation().code(),
        "DIRECTORY_HEALTHCARE_SERVICE_DEACTIVATE",
        "HealthcareService",
        Optional.of(id),
        Optional.of(organizationReference(reference.organizationId())));
    Facility facility =
        store
            .findFacility(reference.facilityId())
            .orElseThrow(DirectoryApplicationService::notFound);
    requireFacilityCurationScope(
        context, "DIRECTORY_HEALTHCARE_SERVICE_DEACTIVATE", "HealthcareService", facility);
    CommandReceiptKey receiptKey =
        commandKey(
            context,
            "healthcare-service.deactivate",
            "healthcare-services/" + id,
            rawIdempotencyKey);
    executeIdempotent(
        receiptKey,
        fingerprint,
        204,
        () -> {
          HealthcareService current =
              store.lockHealthcareService(id).orElseThrow(DirectoryApplicationService::notFound);
          Facility currentFacility =
              store
                  .lockFacility(current.facilityId())
                  .orElseThrow(DirectoryApplicationService::notFound);
          requireFacilityCurationScope(
              context,
              "DIRECTORY_HEALTHCARE_SERVICE_DEACTIVATE",
              "HealthcareService",
              currentFacility);
          if (current.status() != DirectoryLifecycleStatus.ACTIVE) {
            throw inactive();
          }
          requireExpectedVersion(current.version(), expectedVersion);
          HealthcareService deactivated = current.deactivate(provenance);
          store.appendHealthcareServiceRevision(current, provenance.reasonCode());
          if (!store.updateHealthcareService(deactivated, expectedVersion, clock.instant())) {
            throw versionConflict();
          }
          auditMutation(
              context,
              "DIRECTORY_HEALTHCARE_SERVICE_DEACTIVATE",
              "HealthcareService",
              id,
              provenance.reasonCode(),
              Optional.of(organizationReference(current.organizationId())),
              Optional.of(facilityReference(current.facilityId())));
          return id;
        });
  }

  public InternalId createCareCapability(
      ExecutionContext context,
      String code,
      String displayName,
      Optional<InternalId> parentCapabilityId,
      DirectorySource source,
      DirectoryProvenance provenance,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(
        context,
        DirectoryAuthorities.vocabularyAdministration().code(),
        "DIRECTORY_CARE_CAPABILITY_CREATE",
        "CareCapability",
        Optional.empty(),
        Optional.empty());
    requirePlatformVocabularyScope(context, "DIRECTORY_CARE_CAPABILITY_CREATE");
    requireCapabilityCode(code);
    requireSourceType(source, "CareCapability");
    CommandReceiptKey receiptKey =
        commandKey(context, "care-capability.create", "care-capabilities", rawIdempotencyKey);
    return executeIdempotent(
        receiptKey,
        fingerprint,
        201,
        () -> {
          if (store.findCareCapabilityByCode(code).isPresent()) {
            throw capabilityCodeConflict();
          }
          parentCapabilityId.ifPresent(
              parentId -> {
                CareCapability parent =
                    store
                        .lockCareCapability(parentId)
                        .orElseThrow(DirectoryApplicationService::notFound);
                if (parent.status() != DirectoryLifecycleStatus.ACTIVE) {
                  throw inactive();
                }
              });
          InternalId id = InternalId.generate();
          CareCapability capability =
              new CareCapability(
                  id,
                  code,
                  normalizedName(displayName),
                  parentCapabilityId,
                  source,
                  provenance,
                  DirectoryLifecycleStatus.ACTIVE,
                  1);
          store.insertCareCapability(capability);
          auditMutation(
              context,
              "DIRECTORY_CARE_CAPABILITY_CREATE",
              "CareCapability",
              id,
              provenance.reasonCode(),
              Optional.empty(),
              Optional.empty());
          return id;
        });
  }

  public DirectoryCareCapabilityPage listCareCapabilities(
      ExecutionContext context, Integer requestedPage, Integer requestedSize) {
    requireAuthority(
        context,
        DirectoryAuthorities.read().code(),
        "DIRECTORY_CARE_CAPABILITY_LIST_READ",
        "CareCapabilityVocabulary",
        Optional.empty(),
        Optional.empty());
    PageBounds page = validatedPageBounds(requestedPage, requestedSize);
    auditVocabularyRead(context);
    List<CareCapability> capabilities =
        store.listCareCapabilities(true, page.size() + 1, page.offset());
    return new DirectoryCareCapabilityPage(
        capabilities.stream().limit(page.size()).toList(),
        page.page(),
        page.size(),
        capabilities.size() > page.size());
  }

  public void correctCareCapabilityDisplayName(
      ExecutionContext context,
      InternalId id,
      long expectedVersion,
      String displayName,
      DirectoryProvenance provenance,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(
        context,
        DirectoryAuthorities.vocabularyAdministration().code(),
        "DIRECTORY_CARE_CAPABILITY_CORRECT",
        "CareCapability",
        Optional.of(id),
        Optional.empty());
    requirePlatformVocabularyScope(context, "DIRECTORY_CARE_CAPABILITY_CORRECT");
    CommandReceiptKey receiptKey =
        commandKey(
            context, "care-capability.correct", "care-capabilities/" + id, rawIdempotencyKey);
    executeIdempotent(
        receiptKey,
        fingerprint,
        204,
        () -> {
          CareCapability current =
              store.lockCareCapability(id).orElseThrow(DirectoryApplicationService::notFound);
          if (current.status() != DirectoryLifecycleStatus.ACTIVE) {
            throw inactive();
          }
          requireExpectedVersion(current.version(), expectedVersion);
          CareCapability corrected =
              current.correctDisplayName(normalizedName(displayName), provenance);
          store.appendCareCapabilityRevision(current, provenance.reasonCode());
          if (!store.updateCareCapability(corrected, expectedVersion, clock.instant())) {
            throw versionConflict();
          }
          auditMutation(
              context,
              "DIRECTORY_CARE_CAPABILITY_CORRECT",
              "CareCapability",
              id,
              provenance.reasonCode(),
              Optional.empty(),
              Optional.empty());
          return id;
        });
  }

  public void deactivateCareCapability(
      ExecutionContext context,
      InternalId id,
      long expectedVersion,
      DirectoryProvenance provenance,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(
        context,
        DirectoryAuthorities.vocabularyAdministration().code(),
        "DIRECTORY_CARE_CAPABILITY_DEACTIVATE",
        "CareCapability",
        Optional.of(id),
        Optional.empty());
    requirePlatformVocabularyScope(context, "DIRECTORY_CARE_CAPABILITY_DEACTIVATE");
    CommandReceiptKey receiptKey =
        commandKey(
            context, "care-capability.deactivate", "care-capabilities/" + id, rawIdempotencyKey);
    executeIdempotent(
        receiptKey,
        fingerprint,
        204,
        () -> {
          CareCapability current =
              store.lockCareCapability(id).orElseThrow(DirectoryApplicationService::notFound);
          if (current.status() != DirectoryLifecycleStatus.ACTIVE) {
            throw inactive();
          }
          requireExpectedVersion(current.version(), expectedVersion);
          CareCapability deactivated = current.deactivate(provenance);
          store.appendCareCapabilityRevision(current, provenance.reasonCode());
          if (!store.updateCareCapability(deactivated, expectedVersion, clock.instant())) {
            throw versionConflict();
          }
          auditMutation(
              context,
              "DIRECTORY_CARE_CAPABILITY_DEACTIVATE",
              "CareCapability",
              id,
              provenance.reasonCode(),
              Optional.empty(),
              Optional.empty());
          return id;
        });
  }

  public InternalId createFacilityCapabilityAssertion(
      ExecutionContext context,
      InternalId facilityId,
      String capabilityCode,
      Optional<InternalId> healthcareServiceId,
      CapabilityVerificationStatus verificationStatus,
      DirectorySource source,
      DirectoryProvenance provenance,
      Instant effectiveFrom,
      Optional<Instant> effectiveUntil,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(
        context,
        DirectoryAuthorities.curation().code(),
        "DIRECTORY_CAPABILITY_ASSERTION_CREATE",
        "FacilityCapabilityAssertion",
        Optional.empty(),
        Optional.empty());
    Facility facility =
        store.findFacility(facilityId).orElseThrow(DirectoryApplicationService::notFound);
    requireFacilityCurationScope(
        context, "DIRECTORY_CAPABILITY_ASSERTION_CREATE", "FacilityCapabilityAssertion", facility);
    requireCapabilityCode(capabilityCode);
    requireSourceType(source, "FacilityCapabilityAssertion");
    CareCapability capability =
        store
            .findCareCapabilityByCode(capabilityCode)
            .orElseThrow(DirectoryApplicationService::capabilityUnknown);
    if (capability.status() != DirectoryLifecycleStatus.ACTIVE) {
      throw capabilityInactive();
    }
    healthcareServiceId.ifPresent(
        serviceId -> {
          HealthcareService service =
              store
                  .findHealthcareService(serviceId)
                  .orElseThrow(DirectoryApplicationService::notFound);
          if (!service.facilityId().equals(facilityId)) {
            throw scopeMismatch();
          }
          if (service.status() != DirectoryLifecycleStatus.ACTIVE) {
            throw inactive();
          }
        });
    CommandReceiptKey receiptKey =
        commandKey(
            context,
            "facility-capability-assertion.create",
            "facilities/" + facilityId + "/capability-assertions",
            rawIdempotencyKey);
    return executeIdempotent(
        receiptKey,
        fingerprint,
        201,
        () -> {
          Facility currentFacility =
              store.lockFacility(facilityId).orElseThrow(DirectoryApplicationService::notFound);
          requireActiveFacilityOwner(currentFacility);
          requireFacilityCurationScope(
              context,
              "DIRECTORY_CAPABILITY_ASSERTION_CREATE",
              "FacilityCapabilityAssertion",
              currentFacility);
          CareCapability currentCapability =
              store
                  .lockCareCapability(capability.id())
                  .orElseThrow(DirectoryApplicationService::capabilityUnknown);
          if (currentCapability.status() != DirectoryLifecycleStatus.ACTIVE) {
            throw capabilityInactive();
          }
          validateActiveServiceOwner(healthcareServiceId, facilityId);
          InternalId id = InternalId.generate();
          FacilityCapabilityAssertion assertion =
              new FacilityCapabilityAssertion(
                  id,
                  facilityId,
                  currentCapability.id(),
                  healthcareServiceId,
                  verificationStatus,
                  source,
                  provenance,
                  effectiveFrom,
                  effectiveUntil,
                  1);
          store.insertFacilityCapabilityAssertion(assertion);
          auditMutation(
              context,
              "DIRECTORY_CAPABILITY_ASSERTION_CREATE",
              "FacilityCapabilityAssertion",
              id,
              provenance.reasonCode(),
              Optional.of(organizationReference(currentFacility.managingOrganizationId())),
              Optional.of(facilityReference(facilityId)));
          return id;
        });
  }

  public FacilityCapabilityAssertion getFacilityCapabilityAssertion(
      ExecutionContext context, InternalId id) {
    requireAuthority(
        context,
        DirectoryAuthorities.read().code(),
        "DIRECTORY_CAPABILITY_ASSERTION_READ",
        "FacilityCapabilityAssertion",
        Optional.of(id),
        Optional.empty());
    FacilityCapabilityAssertion assertion =
        store
            .findFacilityCapabilityAssertion(id)
            .orElseThrow(DirectoryApplicationService::notFound);
    Facility facility =
        store
            .findFacility(assertion.facilityId())
            .orElseThrow(DirectoryApplicationService::notFound);
    requireFacilityReadScope(
        context,
        "DIRECTORY_CAPABILITY_ASSERTION_READ",
        "FacilityCapabilityAssertion",
        id,
        facility);
    auditRead(
        context,
        "DIRECTORY_CAPABILITY_ASSERTION_READ",
        "FacilityCapabilityAssertion",
        id,
        Optional.of(organizationReference(facility.managingOrganizationId())),
        Optional.of(facilityReference(facility.id())));
    return assertion;
  }

  public void correctFacilityCapabilityAssertion(
      ExecutionContext context,
      InternalId id,
      long expectedVersion,
      CapabilityVerificationStatus verificationStatus,
      DirectorySource source,
      DirectoryProvenance provenance,
      Instant effectiveFrom,
      Optional<Instant> effectiveUntil,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(
        context,
        DirectoryAuthorities.curation().code(),
        "DIRECTORY_CAPABILITY_ASSERTION_CORRECT",
        "FacilityCapabilityAssertion",
        Optional.of(id),
        Optional.empty());
    FacilityCapabilityAssertion reference =
        store
            .findFacilityCapabilityAssertion(id)
            .orElseThrow(DirectoryApplicationService::notFound);
    Facility facility =
        store
            .findFacility(reference.facilityId())
            .orElseThrow(DirectoryApplicationService::notFound);
    requireFacilityCurationScope(
        context, "DIRECTORY_CAPABILITY_ASSERTION_CORRECT", "FacilityCapabilityAssertion", facility);
    requireSourceType(source, "FacilityCapabilityAssertion");
    CommandReceiptKey receiptKey =
        commandKey(
            context,
            "facility-capability-assertion.correct",
            "capability-assertions/" + id,
            rawIdempotencyKey);
    executeIdempotent(
        receiptKey,
        fingerprint,
        204,
        () -> {
          FacilityCapabilityAssertion current =
              store
                  .lockFacilityCapabilityAssertion(id)
                  .orElseThrow(DirectoryApplicationService::notFound);
          Facility currentFacility =
              store
                  .lockFacility(current.facilityId())
                  .orElseThrow(DirectoryApplicationService::notFound);
          requireFacilityCurationScope(
              context,
              "DIRECTORY_CAPABILITY_ASSERTION_CORRECT",
              "FacilityCapabilityAssertion",
              currentFacility);
          if (current.verificationStatus() == CapabilityVerificationStatus.WITHDRAWN
              || current.verificationStatus() == CapabilityVerificationStatus.SUPERSEDED) {
            throw inactive();
          }
          requireExpectedVersion(current.version(), expectedVersion);
          FacilityCapabilityAssertion corrected =
              current.correct(
                  verificationStatus, source, provenance, effectiveFrom, effectiveUntil);
          store.appendFacilityCapabilityAssertionRevision(current, provenance.reasonCode());
          if (!store.updateFacilityCapabilityAssertion(
              corrected, expectedVersion, clock.instant())) {
            throw versionConflict();
          }
          auditMutation(
              context,
              "DIRECTORY_CAPABILITY_ASSERTION_CORRECT",
              "FacilityCapabilityAssertion",
              id,
              provenance.reasonCode(),
              Optional.of(organizationReference(currentFacility.managingOrganizationId())),
              Optional.of(facilityReference(currentFacility.id())));
          return id;
        });
  }

  public FacilityCapabilityEvaluation evaluateFacilityCapabilities(
      ExecutionContext context, InternalId facilityId, List<String> requestedCapabilityCodes) {
    requireAuthority(
        context,
        DirectoryAuthorities.read().code(),
        "DIRECTORY_FACILITY_CAPABILITY_EVIDENCE_READ",
        "FacilityCapabilityEvidence",
        Optional.of(facilityId),
        Optional.empty());
    Facility facility =
        store.findFacility(facilityId).orElseThrow(DirectoryApplicationService::notFound);
    requireFacilityReadScope(
        context,
        "DIRECTORY_FACILITY_CAPABILITY_EVIDENCE_READ",
        "FacilityCapabilityEvidence",
        facilityId,
        facility);
    List<String> codes = validatedCapabilityRequirements(requestedCapabilityCodes);
    Instant evaluatedAt = clock.instant();
    List<CapabilityRequirementAssessment> assessments =
        codes.stream()
            .map(code -> assessCapabilityRequirement(facility, code, evaluatedAt))
            .toList();
    auditRead(
        context,
        "DIRECTORY_FACILITY_CAPABILITY_EVIDENCE_READ",
        "FacilityCapabilityEvidence",
        facilityId,
        Optional.of(organizationReference(facility.managingOrganizationId())),
        Optional.of(facilityReference(facilityId)));
    boolean facilityActive = facility.status() == DirectoryLifecycleStatus.ACTIVE;
    boolean requirementsSatisfied =
        facilityActive
            && assessments.stream()
                .allMatch(item -> item.outcome() == CapabilityRequirementOutcome.SATISFIED);
    return new FacilityCapabilityEvaluation(
        facility.id(), facility.status(), evaluatedAt, requirementsSatisfied, assessments);
  }

  private CapabilityRequirementAssessment assessCapabilityRequirement(
      Facility facility, String code, Instant evaluatedAt) {
    Optional<CareCapability> capability = store.findCareCapabilityByCode(code);
    if (capability.isEmpty()) {
      return new CapabilityRequirementAssessment(
          code, CapabilityRequirementOutcome.UNKNOWN_CAPABILITY, List.of());
    }
    if (capability.orElseThrow().status() != DirectoryLifecycleStatus.ACTIVE) {
      return new CapabilityRequirementAssessment(
          code, CapabilityRequirementOutcome.INACTIVE_CAPABILITY, List.of());
    }
    List<FacilityCapabilityAssertion> assertions =
        store.listFacilityCapabilityAssertions(facility.id(), capability.orElseThrow().id());
    List<FacilityCapabilityAssertion> qualifying =
        assertions.stream()
            .filter(
                assertion ->
                    assertion.verificationStatus() == CapabilityVerificationStatus.VERIFIED
                        && assertion.isEffectiveAt(evaluatedAt)
                        && serviceIsActive(assertion.healthcareServiceId()))
            .toList();
    if (!qualifying.isEmpty()) {
      return new CapabilityRequirementAssessment(
          code,
          CapabilityRequirementOutcome.SATISFIED,
          qualifying.stream().map(assertion -> assertion.id().toString()).toList());
    }
    if (assertions.isEmpty()) {
      return new CapabilityRequirementAssessment(
          code, CapabilityRequirementOutcome.NO_ASSERTION, List.of());
    }
    if (assertions.stream()
        .anyMatch(
            assertion ->
                assertion.verificationStatus() == CapabilityVerificationStatus.VERIFIED
                    && assertion.isEffectiveAt(evaluatedAt)
                    && assertion.healthcareServiceId().isPresent()
                    && !serviceIsActive(assertion.healthcareServiceId()))) {
      return new CapabilityRequirementAssessment(
          code, CapabilityRequirementOutcome.INACTIVE_OFFERING, List.of());
    }
    if (assertions.stream()
        .anyMatch(
            assertion -> assertion.verificationStatus() == CapabilityVerificationStatus.VERIFIED)) {
      return new CapabilityRequirementAssessment(
          code, CapabilityRequirementOutcome.NOT_EFFECTIVE, List.of());
    }
    if (assertions.stream()
        .anyMatch(
            assertion -> assertion.verificationStatus() == CapabilityVerificationStatus.DISPUTED)) {
      return new CapabilityRequirementAssessment(
          code, CapabilityRequirementOutcome.DISPUTED, List.of());
    }
    if (assertions.stream()
        .anyMatch(
            assertion ->
                assertion.verificationStatus() == CapabilityVerificationStatus.UNVERIFIED)) {
      return new CapabilityRequirementAssessment(
          code, CapabilityRequirementOutcome.UNVERIFIED, List.of());
    }
    return new CapabilityRequirementAssessment(
        code, CapabilityRequirementOutcome.NO_CURRENT_ASSERTION, List.of());
  }

  private boolean serviceIsActive(Optional<InternalId> serviceId) {
    return serviceId.isEmpty()
        || store
            .findHealthcareService(serviceId.orElseThrow())
            .map(service -> service.status() == DirectoryLifecycleStatus.ACTIVE)
            .orElse(false);
  }

  private void requireFacilityCurationScope(
      ExecutionContext context, String actionCode, String targetType, Facility facility) {
    requireActiveFacilityOwner(facility);
    if (!scopeCanReadFacility(context.scope(), facility)) {
      denyScope(
          context,
          actionCode,
          targetType,
          Optional.of(facility.id()),
          Optional.of(organizationReference(facility.managingOrganizationId())),
          Optional.of(facilityReference(facility.id())));
      throw scopeMismatch();
    }
  }

  private void requireFacilityReadScope(
      ExecutionContext context,
      String actionCode,
      String targetType,
      InternalId targetId,
      Facility facility) {
    if (!scopeCanReadFacility(context.scope(), facility)) {
      denyScope(
          context,
          actionCode,
          targetType,
          Optional.of(targetId),
          Optional.of(organizationReference(facility.managingOrganizationId())),
          Optional.of(facilityReference(facility.id())));
      throw scopeMismatch();
    }
  }

  private void requireActiveFacilityOwner(Facility facility) {
    if (facility.status() != DirectoryLifecycleStatus.ACTIVE) {
      throw inactive();
    }
    Organization organization =
        store
            .lockOrganization(facility.managingOrganizationId())
            .orElseThrow(DirectoryApplicationService::notFound);
    if (organization.status() != DirectoryLifecycleStatus.ACTIVE) {
      throw inactive();
    }
  }

  private void validateLocationOwner(Optional<InternalId> locationId, InternalId facilityId) {
    locationId.ifPresent(
        id -> {
          DirectoryLocation location =
              store.findLocation(id).orElseThrow(DirectoryApplicationService::notFound);
          if (!location.facilityId().equals(facilityId)) {
            throw scopeMismatch();
          }
        });
  }

  private void validateActiveServiceOwner(Optional<InternalId> serviceId, InternalId facilityId) {
    serviceId.ifPresent(
        id -> {
          HealthcareService service =
              store.lockHealthcareService(id).orElseThrow(DirectoryApplicationService::notFound);
          if (!service.facilityId().equals(facilityId)) {
            throw scopeMismatch();
          }
          if (service.status() != DirectoryLifecycleStatus.ACTIVE) {
            throw inactive();
          }
        });
  }

  private void requirePlatformVocabularyScope(ExecutionContext context, String actionCode) {
    if (context.scope().kind() != ExecutionScope.Kind.PLATFORM) {
      denyScope(context, actionCode, "CareCapability", Optional.empty(), Optional.empty());
      throw scopeMismatch();
    }
  }

  private void auditVocabularyRead(ExecutionContext context) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              evidence(
                  context,
                  AuditActionKind.SENSITIVE_READ,
                  "DIRECTORY_CARE_CAPABILITY_LIST_READ",
                  "CareCapabilityVocabulary",
                  Optional.empty(),
                  AuditOutcome.SUCCEEDED,
                  Optional.empty(),
                  AuditAuthorizationOutcome.ALLOWED,
                  Optional.empty(),
                  Optional.empty()));
    } catch (AuditPersistenceUnavailableException failure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The capability vocabulary is not disclosed because access evidence is unavailable.");
    }
  }

  private static List<String> validatedCapabilityRequirements(List<String> requestedCodes) {
    if (requestedCodes == null
        || requestedCodes.isEmpty()
        || requestedCodes.size() > MAX_REQUIRED_CAPABILITIES) {
      throw validation("Provide between 1 and 20 required CareCapability codes.");
    }
    List<String> codes = new ArrayList<>(requestedCodes.size());
    Set<String> unique = new HashSet<>();
    for (String code : requestedCodes) {
      requireCapabilityCode(code);
      if (!unique.add(code)) {
        throw validation("Required CareCapability codes must not contain duplicates.");
      }
      codes.add(code);
    }
    return List.copyOf(codes);
  }

  private static List<String> optionalCapabilityRequirements(List<String> requestedCodes) {
    return requestedCodes == null || requestedCodes.isEmpty()
        ? List.of()
        : validatedCapabilityRequirements(requestedCodes);
  }

  private static void requireCapabilityCode(String code) {
    if (code == null || !CAPABILITY_CODE.matcher(code).matches()) {
      throw validation("CareCapability code must be a stable uppercase token.");
    }
  }

  public enum CapabilityRequirementOutcome {
    SATISFIED,
    UNKNOWN_CAPABILITY,
    INACTIVE_CAPABILITY,
    NO_ASSERTION,
    UNVERIFIED,
    DISPUTED,
    NOT_EFFECTIVE,
    INACTIVE_OFFERING,
    NO_CURRENT_ASSERTION
  }

  public record CapabilityRequirementAssessment(
      String code, CapabilityRequirementOutcome outcome, List<String> evidenceAssertionIds) {
    public CapabilityRequirementAssessment {
      evidenceAssertionIds = List.copyOf(evidenceAssertionIds);
    }
  }

  public record FacilityCapabilityEvaluation(
      InternalId facilityId,
      DirectoryLifecycleStatus facilityStatus,
      Instant evaluatedAt,
      boolean requirementsSatisfied,
      List<CapabilityRequirementAssessment> requirements) {
    public FacilityCapabilityEvaluation {
      requirements = List.copyOf(requirements);
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
      Optional<InternalId> requestedFacilityId,
      List<String> requestedCapabilityCodes,
      Integer requestedPage,
      Integer requestedSize) {
    requireAuthority(
        context,
        DirectoryAuthorities.read().code(),
        "DIRECTORY_FACILITY_NEARBY_READ",
        "FacilitySearch",
        requestedFacilityId,
        requestedOrganizationId.map(DirectoryApplicationService::organizationReference));
    if (!Double.isFinite(radiusMeters)
        || radiusMeters <= 0.0
        || radiusMeters > MAX_SEARCH_RADIUS_METERS) {
      throw coordinateInvalid("Radius must be greater than zero and no more than 500000 meters.");
    }
    PageBounds page = validatedPageBounds(requestedPage, requestedSize);
    SearchScope searchScope =
        resolveSearchScope(
            context,
            requestedOrganizationId,
            requestedFacilityId,
            "DIRECTORY_FACILITY_NEARBY_READ");
    List<String> requiredCapabilityCodes = optionalCapabilityRequirements(requestedCapabilityCodes);
    Instant evaluatedAt = clock.instant();
    auditSearchRead(
        context,
        searchScope.organizationId(),
        searchScope.facilityId(),
        "DIRECTORY_FACILITY_NEARBY_READ");
    List<LocatedFacilitySearchResult> nearby =
        store.findNearbyFacilities(
            searchScope.organizationId(),
            searchScope.facilityId(),
            center,
            radiusMeters,
            requiredCapabilityCodes,
            evaluatedAt,
            page.size() + 1,
            page.offset());
    List<UnlocatedFacilitySearchResult> unlocated =
        store.findUnlocatedFacilities(
            searchScope.organizationId(),
            searchScope.facilityId(),
            requiredCapabilityCodes,
            evaluatedAt,
            page.size() + 1,
            page.offset());
    boolean hasMoreNearby = nearby.size() > page.size();
    boolean hasMoreUnlocated = unlocated.size() > page.size();
    return new NearbyFacilityPage(
        nearby.stream().limit(page.size()).toList(),
        unlocated.stream().limit(page.size()).toList(),
        page.page(),
        page.size(),
        hasMoreNearby,
        hasMoreUnlocated);
  }

  public DirectoryFacilitySearchPage findFacilities(
      ExecutionContext context,
      Optional<InternalId> requestedOrganizationId,
      Optional<InternalId> requestedFacilityId,
      List<String> requestedCapabilityCodes,
      Integer requestedPage,
      Integer requestedSize) {
    requireAuthority(
        context,
        DirectoryAuthorities.read().code(),
        "DIRECTORY_FACILITY_SEARCH_READ",
        "FacilitySearch",
        requestedFacilityId,
        requestedOrganizationId.map(DirectoryApplicationService::organizationReference));
    PageBounds page = validatedPageBounds(requestedPage, requestedSize);
    SearchScope searchScope =
        resolveSearchScope(
            context,
            requestedOrganizationId,
            requestedFacilityId,
            "DIRECTORY_FACILITY_SEARCH_READ");
    List<String> requiredCapabilityCodes = optionalCapabilityRequirements(requestedCapabilityCodes);
    Instant evaluatedAt = clock.instant();
    auditSearchRead(
        context,
        searchScope.organizationId(),
        searchScope.facilityId(),
        "DIRECTORY_FACILITY_SEARCH_READ");
    List<DirectoryFacilitySearchEntry> facilities =
        store.findFacilities(
            searchScope.organizationId(),
            searchScope.facilityId(),
            requiredCapabilityCodes,
            evaluatedAt,
            page.size() + 1,
            page.offset());
    boolean hasMore = facilities.size() > page.size();
    return new DirectoryFacilitySearchPage(
        facilities.stream().limit(page.size()).toList(), page.page(), page.size(), hasMore);
  }

  private static PageBounds validatedPageBounds(Integer requestedPage, Integer requestedSize) {
    int page = requestedPage == null ? 0 : requestedPage;
    int size = requestedSize == null ? DEFAULT_PAGE_SIZE : requestedSize;
    long offset = (long) page * size;
    if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || offset > MAX_PAGE_OFFSET) {
      throw validation(
          "Page must be non-negative, size must be between 1 and 100, and page offset at most 10000.");
    }
    return new PageBounds(page, size, (int) offset);
  }

  private record PageBounds(int page, int size, int offset) {}

  private SearchScope resolveSearchScope(
      ExecutionContext context,
      Optional<InternalId> requestedOrganizationId,
      Optional<InternalId> requestedFacilityId,
      String actionCode) {
    if (requestedFacilityId.isPresent()) {
      InternalId facilityId = requestedFacilityId.orElseThrow();
      Facility facility =
          store.findFacility(facilityId).orElseThrow(DirectoryApplicationService::notFound);
      boolean organizationFilterConflicts =
          requestedOrganizationId
              .filter(organizationId -> !organizationId.equals(facility.managingOrganizationId()))
              .isPresent();
      if (organizationFilterConflicts && context.scope().kind() == ExecutionScope.Kind.PLATFORM) {
        throw validation("The Facility does not belong to the requested Organization filter.");
      }
      if (organizationFilterConflicts || !scopeCanReadFacility(context.scope(), facility)) {
        denyScope(
            context,
            actionCode,
            "FacilitySearch",
            Optional.of(facilityId),
            Optional.of(organizationReference(facility.managingOrganizationId())),
            Optional.of(facilityReference(facilityId)));
        throw scopeMismatch();
      }
      return new SearchScope(
          Optional.of(facility.managingOrganizationId()), Optional.of(facilityId));
    }

    return switch (context.scope().kind()) {
      case PLATFORM -> new SearchScope(requestedOrganizationId, Optional.empty());
      case ORGANIZATION -> {
        InternalId scopeOrganizationId =
            referenceId(context.scope().organization().orElseThrow(), "Organization");
        if (requestedOrganizationId.isPresent()
            && !requestedOrganizationId.orElseThrow().equals(scopeOrganizationId)) {
          denyScope(
              context,
              actionCode,
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
              actionCode,
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

  private void auditSearchRead(
      ExecutionContext context,
      Optional<InternalId> organizationId,
      Optional<InternalId> facilityId,
      String actionCode) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              evidence(
                  context,
                  AuditActionKind.SENSITIVE_READ,
                  actionCode,
                  "FacilitySearch",
                  Optional.empty(),
                  AuditOutcome.SUCCEEDED,
                  Optional.empty(),
                  AuditAuthorizationOutcome.ALLOWED,
                  organizationId.map(DirectoryApplicationService::organizationReference),
                  facilityId.map(DirectoryApplicationService::facilityReference)));
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
      int page,
      int size,
      boolean hasMoreNearby,
      boolean hasMoreUnlocated) {
    public NearbyFacilityPage {
      nearby = List.copyOf(nearby);
      unlocated = List.copyOf(unlocated);
    }
  }

  public record DirectoryFacilitySearchPage(
      List<DirectoryFacilitySearchEntry> facilities, int page, int size, boolean hasMore) {
    public DirectoryFacilitySearchPage {
      facilities = List.copyOf(facilities);
    }
  }

  public record DirectoryCareCapabilityPage(
      List<CareCapability> items, int page, int size, boolean hasMore) {
    public DirectoryCareCapabilityPage {
      items = List.copyOf(items);
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

  private static PlatformProblemException capabilityUnknown() {
    return problem(
        HttpStatus.BAD_REQUEST,
        PlatformErrorCode.DIRECTORY_CAPABILITY_UNKNOWN,
        "The requested CareCapability code is not defined in this Directory vocabulary.");
  }

  private static PlatformProblemException capabilityInactive() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.DIRECTORY_INACTIVE,
        "The inactive CareCapability cannot be used for this operation.");
  }

  private static PlatformProblemException capabilityCodeConflict() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.DIRECTORY_CAPABILITY_CODE_CONFLICT,
        "The CareCapability code is already defined in this Directory vocabulary.");
  }

  private static PlatformProblemException identifierConflict() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.DIRECTORY_IDENTIFIER_CONFLICT,
        "An active external identifier with this issuer, resource type, and value already exists for this Directory concept type.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
