package org.ayuvahak.directory.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.directory.domain.DirectoryAuthorities;
import org.ayuvahak.directory.domain.DirectoryConceptType;
import org.ayuvahak.directory.domain.DirectoryExternalIdentifier;
import org.ayuvahak.directory.domain.DirectoryLifecycleStatus;
import org.ayuvahak.directory.domain.DirectoryLocation;
import org.ayuvahak.directory.domain.DirectoryProvenance;
import org.ayuvahak.directory.domain.DirectorySource;
import org.ayuvahak.directory.domain.ExternalIdentifierStatus;
import org.ayuvahak.directory.domain.Facility;
import org.ayuvahak.directory.domain.HealthcareService;
import org.ayuvahak.directory.domain.Organization;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.audit.application.AuditPersistenceUnavailableException;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditActionKind;
import org.ayuvahak.platform.audit.domain.AuditAuthenticationEvidence;
import org.ayuvahak.platform.audit.domain.AuditAuthorizationOutcome;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.audit.domain.AuditOutcome;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.AuthorizationDecision;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Applies a mapped external fact through Directory-owned persistence and audit boundaries. */
@Service
@Lazy
public class DirectorySourceProjectionImportService implements DirectorySourceProjectionImportPort {

  private static final String ACTION_CODE = "DIRECTORY_MCSD_SOURCE_IMPORT";

  private final DirectoryStore store;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final TransactionTemplate transaction;
  private final DirectorySourceProjectionReconciliation reconciliation;
  private final Clock clock;

  public DirectorySourceProjectionImportService(
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
    this.reconciliation = new DirectorySourceProjectionReconciliation();
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public DirectorySourceProjectionImportResult importSourceProjection(
      ExecutionContext context, DirectorySourceProjection projection) {
    Objects.requireNonNull(context, "execution context");
    Objects.requireNonNull(projection, "source projection");
    requireServiceContext(context);
    requireCurationAuthority(context);

    if (projection instanceof DirectorySourceProjection.EndpointMetadata) {
      // An Endpoint is only untrusted discovery metadata. It is deliberately not persisted or
      // resolved here; Phase 9 owns the separate administrator-approved network trust profile.
      return result(DirectorySourceProjectionReconciliation.Outcome.METADATA_ONLY, null, null);
    }
    try {
      return transaction.execute(status -> importProjection(context, projection));
    } catch (AuditPersistenceUnavailableException failure) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The Directory source fact could not be committed with its required audit evidence.");
    } catch (DataAccessException failure) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.DIRECTORY_UNAVAILABLE,
          "The Directory source fact could not be reconciled safely.");
    }
  }

  private DirectorySourceProjectionImportResult importProjection(
      ExecutionContext context, DirectorySourceProjection projection) {
    return switch (projection) {
      case DirectorySourceProjection.OrganizationFact organization ->
          importOrganization(context, organization);
      case DirectorySourceProjection.FacilityFact facility -> importFacility(context, facility);
      case DirectorySourceProjection.LocationFact location -> importLocation(context, location);
      case DirectorySourceProjection.HealthcareServiceFact service ->
          importHealthcareService(context, service);
      case DirectorySourceProjection.EndpointMetadata ignored ->
          result(DirectorySourceProjectionReconciliation.Outcome.METADATA_ONLY, null, null);
    };
  }

  private DirectorySourceProjectionImportResult importFacility(
      ExecutionContext context, DirectorySourceProjection.FacilityFact incoming) {
    ExternalReference identity = incoming.metadata().sourceIdentity();
    store.lockMcsdSourceIdentity(identity);
    Optional<DirectorySourceProjectionState> current = store.lockMcsdSourceProjection(identity);
    DirectorySourceProjectionReconciliation.Outcome outcome =
        reconciliation.decide(current, incoming, DirectoryConceptType.FACILITY, true);

    if (outcome == DirectorySourceProjectionReconciliation.Outcome.CREATE) {
      return createFacility(context, incoming, current);
    }
    if (outcome == DirectorySourceProjectionReconciliation.Outcome.UPDATE) {
      return updateFacility(context, incoming, current.orElseThrow());
    }
    if (outcome == DirectorySourceProjectionReconciliation.Outcome.DEACTIVATE) {
      return deactivateFacility(context, incoming, current.orElseThrow());
    }
    return recordNonActionable(incoming, current, DirectoryConceptType.FACILITY, outcome);
  }

  private DirectorySourceProjectionImportResult createFacility(
      ExecutionContext context,
      DirectorySourceProjection.FacilityFact incoming,
      Optional<DirectorySourceProjectionState> previousState) {
    if (previousState.isPresent() && previousState.orElseThrow().conceptId().isPresent()) {
      return result(
          DirectorySourceProjectionReconciliation.Outcome.CONFLICT,
          DirectoryConceptType.FACILITY,
          previousState.orElseThrow().conceptId().orElseThrow());
    }
    if (store.findActiveExternalIdentifier(incoming.metadata().sourceIdentity()).isPresent()) {
      return result(
          DirectorySourceProjectionReconciliation.Outcome.CONFLICT,
          DirectoryConceptType.FACILITY,
          null);
    }
    Optional<InternalId> organizationId =
        resolveActiveOrganization(incoming.managingOrganization());
    if (organizationId.isEmpty()) {
      return recordUnprojected(
          incoming,
          previousState,
          DirectoryConceptType.FACILITY,
          DirectorySourceProjectionReconciliation.Outcome.RECONCILIATION_REQUIRED);
    }

    Instant now = clock.instant();
    DirectoryProvenance provenance =
        provenance(context, incoming.metadata().sourceUpdatedAt(), now);
    InternalId facilityId = InternalId.generate();
    Facility facility =
        new Facility(
            facilityId,
            organizationId.orElseThrow(),
            incoming.name(),
            new DirectorySource(incoming.metadata().sourceIdentity()),
            provenance,
            DirectoryLifecycleStatus.ACTIVE,
            1);
    InternalId locationId = InternalId.generate();
    DirectoryLocation primaryLocation =
        new DirectoryLocation(
            locationId,
            facilityId,
            incoming.name(),
            incoming.coordinate(),
            true,
            new DirectorySource(incoming.metadata().sourceIdentity()),
            provenance,
            1);
    store.insertFacility(facility);
    store.insertLocation(primaryLocation);
    store.insertExternalIdentifier(
        externalIdentifier(
            DirectoryConceptType.FACILITY,
            facilityId,
            incoming.metadata().sourceIdentity(),
            provenance,
            now));
    writeProjection(
        incoming,
        DirectoryConceptType.FACILITY,
        Optional.of(facilityId),
        DirectorySourceProjectionReconciliation.Outcome.CREATE,
        previousState,
        now);
    recordMutation(context, "Facility", facilityId, AuditOutcome.SUCCEEDED, "SOURCE_FACT_IMPORTED");
    return result(
        DirectorySourceProjectionReconciliation.Outcome.CREATE,
        DirectoryConceptType.FACILITY,
        facilityId);
  }

  private DirectorySourceProjectionImportResult updateFacility(
      ExecutionContext context,
      DirectorySourceProjection.FacilityFact incoming,
      DirectorySourceProjectionState accepted) {
    InternalId id = accepted.conceptId().orElseThrow();
    Facility current =
        store
            .lockFacility(id)
            .orElseThrow(DirectorySourceProjectionImportService::ownerFactMissing);
    Optional<InternalId> organizationId =
        resolveActiveOrganization(incoming.managingOrganization());
    if (organizationId.isEmpty()) {
      return result(
          DirectorySourceProjectionReconciliation.Outcome.RECONCILIATION_REQUIRED,
          DirectoryConceptType.FACILITY,
          id);
    }
    if (!current.managingOrganizationId().equals(organizationId.orElseThrow())) {
      return recordNonActionable(
          incoming,
          Optional.of(accepted),
          DirectoryConceptType.FACILITY,
          DirectorySourceProjectionReconciliation.Outcome.CONFLICT);
    }
    if (!hasExpectedIdentifier(
        incoming.metadata().sourceIdentity(), DirectoryConceptType.FACILITY, id)) {
      return result(
          DirectorySourceProjectionReconciliation.Outcome.CONFLICT,
          DirectoryConceptType.FACILITY,
          id);
    }
    if (current.status() != DirectoryLifecycleStatus.ACTIVE) {
      return result(
          DirectorySourceProjectionReconciliation.Outcome.RECONCILIATION_REQUIRED,
          DirectoryConceptType.FACILITY,
          id);
    }
    List<DirectoryLocation> locations = store.listLocations(id);
    List<DirectoryLocation> primary =
        locations.stream().filter(DirectoryLocation::primary).toList();
    if (primary.size() != 1) {
      return result(
          DirectorySourceProjectionReconciliation.Outcome.RECONCILIATION_REQUIRED,
          DirectoryConceptType.FACILITY,
          id);
    }

    Instant now = clock.instant();
    DirectoryProvenance provenance =
        provenance(context, incoming.metadata().sourceUpdatedAt(), now);
    Facility corrected = current.correct(incoming.name(), provenance);
    DirectoryLocation existingPrimary = primary.getFirst();
    DirectoryLocation correctedPrimary =
        existingPrimary.correct(incoming.name(), incoming.coordinate(), true, provenance);
    store.appendFacilityRevision(current, provenance.reasonCode());
    store.appendLocationRevision(existingPrimary, provenance.reasonCode());
    if (!store.updateFacility(corrected, current.version(), now)
        || !store.updateLocation(correctedPrimary, existingPrimary.version(), now)) {
      throw versionConflict();
    }
    writeProjection(
        incoming,
        DirectoryConceptType.FACILITY,
        Optional.of(id),
        DirectorySourceProjectionReconciliation.Outcome.UPDATE,
        Optional.of(accepted),
        now);
    recordMutation(context, "Facility", id, AuditOutcome.SUCCEEDED, "SOURCE_FACT_UPDATED");
    return result(
        DirectorySourceProjectionReconciliation.Outcome.UPDATE, DirectoryConceptType.FACILITY, id);
  }

  private DirectorySourceProjectionImportResult deactivateFacility(
      ExecutionContext context,
      DirectorySourceProjection.FacilityFact incoming,
      DirectorySourceProjectionState accepted) {
    InternalId id = accepted.conceptId().orElseThrow();
    Facility current =
        store
            .lockFacility(id)
            .orElseThrow(DirectorySourceProjectionImportService::ownerFactMissing);
    if (!hasExpectedIdentifier(
        incoming.metadata().sourceIdentity(), DirectoryConceptType.FACILITY, id)) {
      return result(
          DirectorySourceProjectionReconciliation.Outcome.CONFLICT,
          DirectoryConceptType.FACILITY,
          id);
    }
    if (current.status() != DirectoryLifecycleStatus.ACTIVE) {
      return recordNonActionable(
          incoming,
          Optional.of(accepted),
          DirectoryConceptType.FACILITY,
          DirectorySourceProjectionReconciliation.Outcome.UNCHANGED);
    }
    Instant now = clock.instant();
    DirectoryProvenance provenance =
        provenance(context, incoming.metadata().sourceUpdatedAt(), now);
    Facility deactivated = current.deactivate(provenance);
    store.appendFacilityRevision(current, provenance.reasonCode());
    if (!store.updateFacility(deactivated, current.version(), now)) {
      throw versionConflict();
    }
    writeProjection(
        incoming,
        DirectoryConceptType.FACILITY,
        Optional.of(id),
        DirectorySourceProjectionReconciliation.Outcome.DEACTIVATE,
        Optional.of(accepted),
        now);
    recordMutation(context, "Facility", id, AuditOutcome.SUCCEEDED, "SOURCE_FACT_DEACTIVATED");
    return result(
        DirectorySourceProjectionReconciliation.Outcome.DEACTIVATE,
        DirectoryConceptType.FACILITY,
        id);
  }

  private DirectorySourceProjectionImportResult importLocation(
      ExecutionContext context, DirectorySourceProjection.LocationFact incoming) {
    ExternalReference identity = incoming.metadata().sourceIdentity();
    store.lockMcsdSourceIdentity(identity);
    Optional<DirectorySourceProjectionState> current = store.lockMcsdSourceProjection(identity);
    DirectorySourceProjectionReconciliation.Outcome outcome =
        reconciliation.decide(current, incoming, DirectoryConceptType.LOCATION, false);
    if (outcome == DirectorySourceProjectionReconciliation.Outcome.CREATE) {
      return createChildLocation(context, incoming, current);
    }
    if (outcome == DirectorySourceProjectionReconciliation.Outcome.UPDATE) {
      return updateChildLocation(context, incoming, current.orElseThrow());
    }
    return recordNonActionable(incoming, current, DirectoryConceptType.LOCATION, outcome);
  }

  private DirectorySourceProjectionImportResult createChildLocation(
      ExecutionContext context,
      DirectorySourceProjection.LocationFact incoming,
      Optional<DirectorySourceProjectionState> previousState) {
    if (previousState.isPresent() && previousState.orElseThrow().conceptId().isPresent()) {
      return result(
          DirectorySourceProjectionReconciliation.Outcome.CONFLICT,
          DirectoryConceptType.LOCATION,
          previousState.orElseThrow().conceptId().orElseThrow());
    }
    if (store.findActiveExternalIdentifier(incoming.metadata().sourceIdentity()).isPresent()) {
      return result(
          DirectorySourceProjectionReconciliation.Outcome.CONFLICT,
          DirectoryConceptType.LOCATION,
          null);
    }
    Optional<ResolvedLocation> parent = resolveSourceLocation(incoming.facilityReference());
    if (parent.isEmpty()) {
      return recordUnprojected(
          incoming,
          previousState,
          DirectoryConceptType.LOCATION,
          DirectorySourceProjectionReconciliation.Outcome.RECONCILIATION_REQUIRED);
    }
    ResolvedLocation resolved = parent.orElseThrow();
    if (resolved.facility().status() != DirectoryLifecycleStatus.ACTIVE) {
      return recordUnprojected(
          incoming,
          previousState,
          DirectoryConceptType.LOCATION,
          DirectorySourceProjectionReconciliation.Outcome.RECONCILIATION_REQUIRED);
    }

    Instant now = clock.instant();
    DirectoryProvenance provenance =
        provenance(context, incoming.metadata().sourceUpdatedAt(), now);
    InternalId id = InternalId.generate();
    DirectoryLocation location =
        new DirectoryLocation(
            id,
            resolved.facility().id(),
            incoming.name(),
            incoming.coordinate(),
            false,
            new DirectorySource(incoming.metadata().sourceIdentity()),
            provenance,
            1);
    store.insertLocation(location);
    store.insertExternalIdentifier(
        externalIdentifier(
            DirectoryConceptType.LOCATION,
            id,
            incoming.metadata().sourceIdentity(),
            provenance,
            now));
    writeProjection(
        incoming,
        DirectoryConceptType.LOCATION,
        Optional.of(id),
        DirectorySourceProjectionReconciliation.Outcome.CREATE,
        previousState,
        now);
    recordMutation(
        context, "DirectoryLocation", id, AuditOutcome.SUCCEEDED, "SOURCE_FACT_IMPORTED");
    return result(
        DirectorySourceProjectionReconciliation.Outcome.CREATE, DirectoryConceptType.LOCATION, id);
  }

  private DirectorySourceProjectionImportResult updateChildLocation(
      ExecutionContext context,
      DirectorySourceProjection.LocationFact incoming,
      DirectorySourceProjectionState accepted) {
    InternalId id = accepted.conceptId().orElseThrow();
    DirectoryLocation current =
        store
            .lockLocation(id)
            .orElseThrow(DirectorySourceProjectionImportService::ownerFactMissing);
    Optional<ResolvedLocation> parent = resolveSourceLocation(incoming.facilityReference());
    if (parent.isEmpty()) {
      return result(
          DirectorySourceProjectionReconciliation.Outcome.RECONCILIATION_REQUIRED,
          DirectoryConceptType.LOCATION,
          id);
    }
    if (!current.facilityId().equals(parent.orElseThrow().facility().id())) {
      return recordNonActionable(
          incoming,
          Optional.of(accepted),
          DirectoryConceptType.LOCATION,
          DirectorySourceProjectionReconciliation.Outcome.CONFLICT);
    }
    if (!hasExpectedIdentifier(
        incoming.metadata().sourceIdentity(), DirectoryConceptType.LOCATION, id)) {
      return result(
          DirectorySourceProjectionReconciliation.Outcome.CONFLICT,
          DirectoryConceptType.LOCATION,
          id);
    }
    Instant now = clock.instant();
    DirectoryProvenance provenance =
        provenance(context, incoming.metadata().sourceUpdatedAt(), now);
    DirectoryLocation corrected =
        current.correct(incoming.name(), incoming.coordinate(), false, provenance);
    store.appendLocationRevision(current, provenance.reasonCode());
    if (!store.updateLocation(corrected, current.version(), now)) {
      throw versionConflict();
    }
    writeProjection(
        incoming,
        DirectoryConceptType.LOCATION,
        Optional.of(id),
        DirectorySourceProjectionReconciliation.Outcome.UPDATE,
        Optional.of(accepted),
        now);
    recordMutation(context, "DirectoryLocation", id, AuditOutcome.SUCCEEDED, "SOURCE_FACT_UPDATED");
    return result(
        DirectorySourceProjectionReconciliation.Outcome.UPDATE, DirectoryConceptType.LOCATION, id);
  }

  private DirectorySourceProjectionImportResult importHealthcareService(
      ExecutionContext context, DirectorySourceProjection.HealthcareServiceFact incoming) {
    ExternalReference identity = incoming.metadata().sourceIdentity();
    store.lockMcsdSourceIdentity(identity);
    Optional<DirectorySourceProjectionState> current = store.lockMcsdSourceProjection(identity);
    DirectorySourceProjectionReconciliation.Outcome outcome =
        reconciliation.decide(current, incoming, DirectoryConceptType.HEALTHCARE_SERVICE, true);
    if (outcome == DirectorySourceProjectionReconciliation.Outcome.CREATE) {
      return createHealthcareService(context, incoming, current);
    }
    if (outcome == DirectorySourceProjectionReconciliation.Outcome.UPDATE) {
      return updateHealthcareService(context, incoming, current.orElseThrow());
    }
    if (outcome == DirectorySourceProjectionReconciliation.Outcome.DEACTIVATE) {
      return deactivateHealthcareService(context, incoming, current.orElseThrow());
    }
    return recordNonActionable(incoming, current, DirectoryConceptType.HEALTHCARE_SERVICE, outcome);
  }

  private DirectorySourceProjectionImportResult createHealthcareService(
      ExecutionContext context,
      DirectorySourceProjection.HealthcareServiceFact incoming,
      Optional<DirectorySourceProjectionState> previousState) {
    if (previousState.isPresent() && previousState.orElseThrow().conceptId().isPresent()) {
      return result(
          DirectorySourceProjectionReconciliation.Outcome.CONFLICT,
          DirectoryConceptType.HEALTHCARE_SERVICE,
          previousState.orElseThrow().conceptId().orElseThrow());
    }
    if (store.findActiveExternalIdentifier(incoming.metadata().sourceIdentity()).isPresent()) {
      return result(
          DirectorySourceProjectionReconciliation.Outcome.CONFLICT,
          DirectoryConceptType.HEALTHCARE_SERVICE,
          null);
    }
    Optional<InternalId> organizationId =
        resolveActiveOrganization(incoming.organizationReference());
    Optional<ResolvedLocation> facilityLocation =
        resolveSourceLocation(incoming.facilityReference());
    if (organizationId.isEmpty() || facilityLocation.isEmpty()) {
      return recordUnprojected(
          incoming,
          previousState,
          DirectoryConceptType.HEALTHCARE_SERVICE,
          DirectorySourceProjectionReconciliation.Outcome.RECONCILIATION_REQUIRED);
    }
    ResolvedLocation resolved = facilityLocation.orElseThrow();
    if (resolved.facility().status() != DirectoryLifecycleStatus.ACTIVE
        || !resolved.facility().managingOrganizationId().equals(organizationId.orElseThrow())) {
      return recordUnprojected(
          incoming,
          previousState,
          DirectoryConceptType.HEALTHCARE_SERVICE,
          DirectorySourceProjectionReconciliation.Outcome.RECONCILIATION_REQUIRED);
    }
    Instant now = clock.instant();
    DirectoryProvenance provenance =
        provenance(context, incoming.metadata().sourceUpdatedAt(), now);
    InternalId id = InternalId.generate();
    HealthcareService service =
        new HealthcareService(
            id,
            organizationId.orElseThrow(),
            resolved.facility().id(),
            Optional.of(resolved.location().id()),
            incoming.name(),
            new DirectorySource(incoming.metadata().sourceIdentity()),
            provenance,
            DirectoryLifecycleStatus.ACTIVE,
            1);
    store.insertHealthcareService(service);
    store.insertExternalIdentifier(
        externalIdentifier(
            DirectoryConceptType.HEALTHCARE_SERVICE,
            id,
            incoming.metadata().sourceIdentity(),
            provenance,
            now));
    writeProjection(
        incoming,
        DirectoryConceptType.HEALTHCARE_SERVICE,
        Optional.of(id),
        DirectorySourceProjectionReconciliation.Outcome.CREATE,
        previousState,
        now);
    recordMutation(
        context, "HealthcareService", id, AuditOutcome.SUCCEEDED, "SOURCE_FACT_IMPORTED");
    return result(
        DirectorySourceProjectionReconciliation.Outcome.CREATE,
        DirectoryConceptType.HEALTHCARE_SERVICE,
        id);
  }

  private DirectorySourceProjectionImportResult updateHealthcareService(
      ExecutionContext context,
      DirectorySourceProjection.HealthcareServiceFact incoming,
      DirectorySourceProjectionState accepted) {
    InternalId id = accepted.conceptId().orElseThrow();
    HealthcareService current =
        store
            .lockHealthcareService(id)
            .orElseThrow(DirectorySourceProjectionImportService::ownerFactMissing);
    Optional<InternalId> organizationId =
        resolveActiveOrganization(incoming.organizationReference());
    Optional<ResolvedLocation> facilityLocation =
        resolveSourceLocation(incoming.facilityReference());
    if (organizationId.isEmpty() || facilityLocation.isEmpty()) {
      return result(
          DirectorySourceProjectionReconciliation.Outcome.RECONCILIATION_REQUIRED,
          DirectoryConceptType.HEALTHCARE_SERVICE,
          id);
    }
    ResolvedLocation resolved = facilityLocation.orElseThrow();
    if (!hasExpectedIdentifier(
        incoming.metadata().sourceIdentity(), DirectoryConceptType.HEALTHCARE_SERVICE, id)) {
      return result(
          DirectorySourceProjectionReconciliation.Outcome.CONFLICT,
          DirectoryConceptType.HEALTHCARE_SERVICE,
          id);
    }
    if (!current.organizationId().equals(organizationId.orElseThrow())
        || !current.facilityId().equals(resolved.facility().id())) {
      return recordNonActionable(
          incoming,
          Optional.of(accepted),
          DirectoryConceptType.HEALTHCARE_SERVICE,
          DirectorySourceProjectionReconciliation.Outcome.CONFLICT);
    }
    if (current.status() != DirectoryLifecycleStatus.ACTIVE) {
      return result(
          DirectorySourceProjectionReconciliation.Outcome.RECONCILIATION_REQUIRED,
          DirectoryConceptType.HEALTHCARE_SERVICE,
          id);
    }
    Instant now = clock.instant();
    DirectoryProvenance provenance =
        provenance(context, incoming.metadata().sourceUpdatedAt(), now);
    HealthcareService corrected =
        current.correct(incoming.name(), Optional.of(resolved.location().id()), provenance);
    store.appendHealthcareServiceRevision(current, provenance.reasonCode());
    if (!store.updateHealthcareService(corrected, current.version(), now)) {
      throw versionConflict();
    }
    writeProjection(
        incoming,
        DirectoryConceptType.HEALTHCARE_SERVICE,
        Optional.of(id),
        DirectorySourceProjectionReconciliation.Outcome.UPDATE,
        Optional.of(accepted),
        now);
    recordMutation(context, "HealthcareService", id, AuditOutcome.SUCCEEDED, "SOURCE_FACT_UPDATED");
    return result(
        DirectorySourceProjectionReconciliation.Outcome.UPDATE,
        DirectoryConceptType.HEALTHCARE_SERVICE,
        id);
  }

  private DirectorySourceProjectionImportResult deactivateHealthcareService(
      ExecutionContext context,
      DirectorySourceProjection.HealthcareServiceFact incoming,
      DirectorySourceProjectionState accepted) {
    InternalId id = accepted.conceptId().orElseThrow();
    HealthcareService current =
        store
            .lockHealthcareService(id)
            .orElseThrow(DirectorySourceProjectionImportService::ownerFactMissing);
    if (!hasExpectedIdentifier(
        incoming.metadata().sourceIdentity(), DirectoryConceptType.HEALTHCARE_SERVICE, id)) {
      return result(
          DirectorySourceProjectionReconciliation.Outcome.CONFLICT,
          DirectoryConceptType.HEALTHCARE_SERVICE,
          id);
    }
    if (current.status() != DirectoryLifecycleStatus.ACTIVE) {
      return recordNonActionable(
          incoming,
          Optional.of(accepted),
          DirectoryConceptType.HEALTHCARE_SERVICE,
          DirectorySourceProjectionReconciliation.Outcome.UNCHANGED);
    }
    Instant now = clock.instant();
    DirectoryProvenance provenance =
        provenance(context, incoming.metadata().sourceUpdatedAt(), now);
    HealthcareService deactivated = current.deactivate(provenance);
    store.appendHealthcareServiceRevision(current, provenance.reasonCode());
    if (!store.updateHealthcareService(deactivated, current.version(), now)) {
      throw versionConflict();
    }
    writeProjection(
        incoming,
        DirectoryConceptType.HEALTHCARE_SERVICE,
        Optional.of(id),
        DirectorySourceProjectionReconciliation.Outcome.DEACTIVATE,
        Optional.of(accepted),
        now);
    recordMutation(
        context, "HealthcareService", id, AuditOutcome.SUCCEEDED, "SOURCE_FACT_DEACTIVATED");
    return result(
        DirectorySourceProjectionReconciliation.Outcome.DEACTIVATE,
        DirectoryConceptType.HEALTHCARE_SERVICE,
        id);
  }

  private Optional<InternalId> resolveActiveOrganization(ExternalReference reference) {
    if (!"Organization".equals(reference.resourceType())) {
      return Optional.empty();
    }
    Optional<DirectoryExternalIdentifier> identifier =
        store.findActiveExternalIdentifier(reference);
    if (identifier.isEmpty()
        || identifier.orElseThrow().conceptType() != DirectoryConceptType.ORGANIZATION) {
      return Optional.empty();
    }
    return store
        .findOrganization(identifier.orElseThrow().conceptId())
        .filter(value -> value.status() == DirectoryLifecycleStatus.ACTIVE)
        .map(Organization::id);
  }

  private Optional<ResolvedLocation> resolveSourceLocation(ExternalReference reference) {
    if (!"Location".equals(reference.resourceType())) {
      return Optional.empty();
    }
    Optional<DirectorySourceProjectionState> sourceState =
        store.lockMcsdSourceProjection(reference);
    Optional<DirectoryExternalIdentifier> identifier =
        store.findActiveExternalIdentifier(reference);
    if (sourceState.isEmpty()
        || identifier.isEmpty()
        || !sourceState.orElseThrow().sourceActive()) {
      return Optional.empty();
    }
    DirectorySourceProjectionState state = sourceState.orElseThrow();
    DirectoryExternalIdentifier externalIdentifier = identifier.orElseThrow();
    if (state.conceptId().isEmpty() || state.conceptType() != externalIdentifier.conceptType()) {
      return Optional.empty();
    }
    if (externalIdentifier.conceptType() == DirectoryConceptType.FACILITY) {
      Optional<Facility> facility = store.lockFacility(externalIdentifier.conceptId());
      if (facility.isEmpty()) {
        return Optional.empty();
      }
      Optional<DirectoryLocation> primary =
          store.listLocations(facility.orElseThrow().id()).stream()
              .filter(DirectoryLocation::primary)
              .findFirst();
      return primary.map(location -> new ResolvedLocation(facility.orElseThrow(), location));
    }
    if (externalIdentifier.conceptType() == DirectoryConceptType.LOCATION) {
      Optional<DirectoryLocation> location = store.lockLocation(externalIdentifier.conceptId());
      if (location.isEmpty()) {
        return Optional.empty();
      }
      Optional<Facility> facility = store.lockFacility(location.orElseThrow().facilityId());
      return facility.map(value -> new ResolvedLocation(value, location.orElseThrow()));
    }
    return Optional.empty();
  }

  private boolean hasExpectedIdentifier(
      ExternalReference sourceIdentity, DirectoryConceptType conceptType, InternalId conceptId) {
    return store
        .findActiveExternalIdentifier(sourceIdentity)
        .filter(identifier -> identifier.conceptType() == conceptType)
        .filter(identifier -> identifier.conceptId().equals(conceptId))
        .isPresent();
  }

  private DirectorySourceProjectionImportResult recordNonActionable(
      DirectorySourceProjection incoming,
      Optional<DirectorySourceProjectionState> current,
      DirectoryConceptType type,
      DirectorySourceProjectionReconciliation.Outcome outcome) {
    if (outcome == DirectorySourceProjectionReconciliation.Outcome.STALE
        || outcome == DirectorySourceProjectionReconciliation.Outcome.CONFLICT
        || outcome == DirectorySourceProjectionReconciliation.Outcome.RECONCILIATION_REQUIRED) {
      if (current.isPresent()) {
        recordProjectionOutcome(current.orElseThrow(), outcome);
      } else {
        recordUnprojected(incoming, current, type, outcome);
      }
    }
    return result(
        outcome, type, current.flatMap(DirectorySourceProjectionState::conceptId).orElse(null));
  }

  private DirectorySourceProjectionImportResult recordUnprojected(
      DirectorySourceProjection incoming,
      Optional<DirectorySourceProjectionState> current,
      DirectoryConceptType type,
      DirectorySourceProjectionReconciliation.Outcome outcome) {
    if (current.isPresent()) {
      recordProjectionOutcome(current.orElseThrow(), outcome);
    } else {
      store.insertMcsdSourceProjection(
          state(incoming, type, Optional.empty(), outcome, 1, clock.instant()));
    }
    return result(
        outcome, type, current.flatMap(DirectorySourceProjectionState::conceptId).orElse(null));
  }

  private void writeProjection(
      DirectorySourceProjection incoming,
      DirectoryConceptType type,
      Optional<InternalId> conceptId,
      DirectorySourceProjectionReconciliation.Outcome outcome,
      Optional<DirectorySourceProjectionState> previous,
      Instant now) {
    long nextVersion = previous.map(DirectorySourceProjectionState::version).orElse(0L) + 1;
    DirectorySourceProjectionState next =
        state(incoming, type, conceptId, outcome, nextVersion, now);
    if (previous.isPresent()) {
      DirectorySourceProjectionState accepted = previous.orElseThrow();
      next = withProjectionId(next, accepted.projectionId());
      if (!store.updateMcsdSourceProjection(next, accepted.version())) {
        throw versionConflict();
      }
    } else {
      store.insertMcsdSourceProjection(next);
    }
  }

  private DirectorySourceProjectionImportResult importOrganization(
      ExecutionContext context, DirectorySourceProjection.OrganizationFact incoming) {
    ExternalReference identity = incoming.metadata().sourceIdentity();
    store.lockMcsdSourceIdentity(identity);
    Optional<DirectorySourceProjectionState> current = store.lockMcsdSourceProjection(identity);
    DirectorySourceProjectionReconciliation.Outcome outcome =
        reconciliation.decide(current, incoming, DirectoryConceptType.ORGANIZATION, false);

    if (outcome == DirectorySourceProjectionReconciliation.Outcome.CREATE) {
      return createOrganization(context, incoming, current);
    }
    if (outcome == DirectorySourceProjectionReconciliation.Outcome.UPDATE) {
      return updateOrganization(context, incoming, current.orElseThrow());
    }
    DirectorySourceProjectionReconciliation.Outcome finalOutcome =
        outcome == DirectorySourceProjectionReconciliation.Outcome.DEACTIVATE
            ? DirectorySourceProjectionReconciliation.Outcome.RECONCILIATION_REQUIRED
            : outcome;
    return recordNonActionable(incoming, current, DirectoryConceptType.ORGANIZATION, finalOutcome);
  }

  private DirectorySourceProjectionImportResult createOrganization(
      ExecutionContext context,
      DirectorySourceProjection.OrganizationFact incoming,
      Optional<DirectorySourceProjectionState> previousState) {
    if (previousState.isPresent() && previousState.orElseThrow().conceptId().isPresent()) {
      return result(
          DirectorySourceProjectionReconciliation.Outcome.CONFLICT,
          DirectoryConceptType.ORGANIZATION,
          previousState.orElseThrow().conceptId().orElseThrow());
    }
    if (store.findActiveExternalIdentifier(incoming.metadata().sourceIdentity()).isPresent()) {
      return result(
          DirectorySourceProjectionReconciliation.Outcome.CONFLICT,
          DirectoryConceptType.ORGANIZATION,
          null);
    }
    Optional<InternalId> parentId = resolveParentOrganization(incoming.parentOrganization());
    if (incoming.parentOrganization().isPresent() && parentId.isEmpty()) {
      return result(
          DirectorySourceProjectionReconciliation.Outcome.RECONCILIATION_REQUIRED,
          DirectoryConceptType.ORGANIZATION,
          null);
    }

    Instant now = clock.instant();
    DirectoryProvenance provenance =
        provenance(context, incoming.metadata().sourceUpdatedAt(), now);
    InternalId id = InternalId.generate();
    Organization organization =
        new Organization(
            id,
            incoming.name(),
            parentId,
            new DirectorySource(incoming.metadata().sourceIdentity()),
            provenance,
            DirectoryLifecycleStatus.ACTIVE,
            1);
    store.insertOrganization(organization);
    store.insertExternalIdentifier(
        externalIdentifier(
            DirectoryConceptType.ORGANIZATION,
            id,
            incoming.metadata().sourceIdentity(),
            provenance,
            now));
    long nextVersion = previousState.map(DirectorySourceProjectionState::version).orElse(0L) + 1;
    DirectorySourceProjectionState next =
        state(
            incoming,
            DirectoryConceptType.ORGANIZATION,
            Optional.of(id),
            DirectorySourceProjectionReconciliation.Outcome.CREATE,
            nextVersion,
            now);
    if (previousState.isPresent()) {
      DirectorySourceProjectionState previous = previousState.orElseThrow();
      next = withProjectionId(next, previous.projectionId());
      if (!store.updateMcsdSourceProjection(next, previous.version())) {
        throw versionConflict();
      }
    } else {
      store.insertMcsdSourceProjection(next);
    }
    recordMutation(context, id, AuditOutcome.SUCCEEDED, "SOURCE_FACT_IMPORTED");
    return result(
        DirectorySourceProjectionReconciliation.Outcome.CREATE,
        DirectoryConceptType.ORGANIZATION,
        id);
  }

  private DirectorySourceProjectionImportResult updateOrganization(
      ExecutionContext context,
      DirectorySourceProjection.OrganizationFact incoming,
      DirectorySourceProjectionState accepted) {
    InternalId id = accepted.conceptId().orElseThrow();
    Organization current =
        store
            .lockOrganization(id)
            .orElseThrow(DirectorySourceProjectionImportService::ownerFactMissing);
    if (current.status() != DirectoryLifecycleStatus.ACTIVE) {
      return recordNonActionable(
          incoming,
          Optional.of(accepted),
          DirectoryConceptType.ORGANIZATION,
          DirectorySourceProjectionReconciliation.Outcome.RECONCILIATION_REQUIRED);
    }
    Optional<InternalId> parentId = resolveParentOrganization(incoming.parentOrganization());
    if (incoming.parentOrganization().isPresent() && parentId.isEmpty()) {
      return result(
          DirectorySourceProjectionReconciliation.Outcome.RECONCILIATION_REQUIRED,
          DirectoryConceptType.ORGANIZATION,
          id);
    }
    if (!current.parentOrganizationId().equals(parentId)) {
      recordProjectionOutcome(accepted, DirectorySourceProjectionReconciliation.Outcome.CONFLICT);
      return result(
          DirectorySourceProjectionReconciliation.Outcome.CONFLICT,
          DirectoryConceptType.ORGANIZATION,
          id);
    }
    DirectoryExternalIdentifier identifier =
        store
            .findActiveExternalIdentifier(incoming.metadata().sourceIdentity())
            .orElseThrow(DirectorySourceProjectionImportService::ownerFactMissing);
    if (identifier.conceptType() != DirectoryConceptType.ORGANIZATION
        || !identifier.conceptId().equals(id)) {
      return result(
          DirectorySourceProjectionReconciliation.Outcome.CONFLICT,
          DirectoryConceptType.ORGANIZATION,
          id);
    }

    Instant now = clock.instant();
    DirectoryProvenance provenance =
        provenance(context, incoming.metadata().sourceUpdatedAt(), now);
    Organization corrected = current.correct(incoming.name(), provenance);
    store.appendOrganizationRevision(current, provenance.reasonCode());
    if (!store.updateOrganization(corrected, current.version(), now)) {
      throw versionConflict();
    }
    DirectorySourceProjectionState next =
        state(
            incoming,
            DirectoryConceptType.ORGANIZATION,
            Optional.of(id),
            DirectorySourceProjectionReconciliation.Outcome.UPDATE,
            accepted.version() + 1,
            now);
    next = withProjectionId(next, accepted.projectionId());
    if (!store.updateMcsdSourceProjection(next, accepted.version())) {
      throw versionConflict();
    }
    recordMutation(context, id, AuditOutcome.SUCCEEDED, "SOURCE_FACT_UPDATED");
    return result(
        DirectorySourceProjectionReconciliation.Outcome.UPDATE,
        DirectoryConceptType.ORGANIZATION,
        id);
  }

  private Optional<InternalId> resolveParentOrganization(Optional<ExternalReference> reference) {
    if (reference.isEmpty()) {
      return Optional.empty();
    }
    ExternalReference parent = reference.orElseThrow();
    if (!"Organization".equals(parent.resourceType())) {
      return Optional.empty();
    }
    Optional<DirectoryExternalIdentifier> identifier = store.findActiveExternalIdentifier(parent);
    if (identifier.isEmpty()
        || identifier.orElseThrow().conceptType() != DirectoryConceptType.ORGANIZATION) {
      return Optional.empty();
    }
    return store
        .findOrganization(identifier.orElseThrow().conceptId())
        .filter(value -> value.status() == DirectoryLifecycleStatus.ACTIVE)
        .map(Organization::id);
  }

  private void recordProjectionOutcome(
      DirectorySourceProjectionState accepted,
      DirectorySourceProjectionReconciliation.Outcome outcome) {
    DirectorySourceProjectionState next =
        new DirectorySourceProjectionState(
            accepted.projectionId(),
            accepted.sourceIdentity(),
            accepted.conceptType(),
            accepted.conceptId(),
            accepted.profileUri(),
            accepted.opaqueVersionId(),
            accepted.sourceUpdatedAt(),
            accepted.sourceUpdatedAtIsOrderedRevision(),
            accepted.projectionFingerprint(),
            accepted.sourceActive(),
            outcome,
            accepted.version() + 1,
            clock.instant());
    if (!store.updateMcsdSourceProjection(next, accepted.version())) {
      throw versionConflict();
    }
  }

  private DirectorySourceProjectionState state(
      DirectorySourceProjection projection,
      DirectoryConceptType type,
      Optional<InternalId> conceptId,
      DirectorySourceProjectionReconciliation.Outcome outcome,
      long version,
      Instant recordedAt) {
    DirectorySourceProjection.Metadata metadata = projection.metadata();
    return new DirectorySourceProjectionState(
        InternalId.generate(),
        metadata.sourceIdentity(),
        type,
        conceptId,
        metadata.profileUri(),
        metadata.opaqueVersionId(),
        metadata.sourceUpdatedAt(),
        metadata.sourceUpdatedAtIsOrderedRevision(),
        metadata.projectionFingerprint(),
        projection.active(),
        outcome,
        version,
        recordedAt);
  }

  private DirectorySourceProjectionState withProjectionId(
      DirectorySourceProjectionState projection, InternalId projectionId) {
    return new DirectorySourceProjectionState(
        projectionId,
        projection.sourceIdentity(),
        projection.conceptType(),
        projection.conceptId(),
        projection.profileUri(),
        projection.opaqueVersionId(),
        projection.sourceUpdatedAt(),
        projection.sourceUpdatedAtIsOrderedRevision(),
        projection.projectionFingerprint(),
        projection.sourceActive(),
        projection.lastOutcome(),
        projection.version(),
        projection.recordedAt());
  }

  private DirectoryExternalIdentifier externalIdentifier(
      DirectoryConceptType type,
      InternalId conceptId,
      ExternalReference identity,
      DirectoryProvenance provenance,
      Instant now) {
    return new DirectoryExternalIdentifier(
        InternalId.generate(),
        type,
        conceptId,
        identity,
        ExternalIdentifierStatus.ACTIVE,
        Optional.empty(),
        provenance,
        1,
        now,
        now);
  }

  private DirectoryProvenance provenance(
      ExecutionContext context, Optional<Instant> sourceUpdatedAt, Instant now) {
    return new DirectoryProvenance(
        "MCSD_SOURCE_IMPORT",
        sourceUpdatedAt.orElse(now),
        context.actor(),
        now,
        "MCSD_SOURCE_UPDATE");
  }

  private void recordMutation(
      ExecutionContext context, InternalId targetId, AuditOutcome outcome, String reason) {
    recordMutation(context, "Organization", targetId, outcome, reason);
  }

  private void recordMutation(
      ExecutionContext context,
      String resourceType,
      InternalId targetId,
      AuditOutcome outcome,
      String reason) {
    auditWriter
        .getObject()
        .recordMutation(
            new AuditEvidence(
                AuditActionKind.PROTECTED_MUTATION,
                Optional.of(context.actor()),
                Optional.of(context.scope()),
                ACTION_CODE,
                resourceType,
                Optional.of(targetId),
                clock.instant(),
                outcome,
                Optional.of(reason),
                authenticationEvidence(context.authenticationMechanism()),
                AuditAuthorizationOutcome.ALLOWED,
                context.correlationId()));
  }

  private void requireServiceContext(ExecutionContext context) {
    if (context.actor().kind() != ActorKind.SERVICE
        || context.scope().kind() != ExecutionScope.Kind.PLATFORM) {
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.AUTHORIZATION_DENIED,
          "A Directory source import requires a platform-scoped service actor.");
    }
  }

  private void requireCurationAuthority(ExecutionContext context) {
    AuthorizationDecision decision =
        authorization.getObject().evaluate(context, DirectoryAuthorities.curation().code());
    if (decision.isAllowed()) {
      return;
    }
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              new AuditEvidence(
                  AuditActionKind.SECURITY_OUTCOME,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  ACTION_CODE,
                  "DirectorySourceProjection",
                  Optional.empty(),
                  clock.instant(),
                  AuditOutcome.DENIED,
                  Optional.of("AUTHORIZATION_DENIED"),
                  authenticationEvidence(context.authenticationMechanism()),
                  AuditAuthorizationOutcome.DENIED,
                  context.correlationId()));
    } catch (AuditPersistenceUnavailableException failure) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The import remains denied; its required audit record is unavailable.");
    }
    PlatformErrorCode code =
        decision.denialReason().orElse(null)
                == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE
            ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            : PlatformErrorCode.AUTHORIZATION_DENIED;
    HttpStatus status =
        code == PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            ? HttpStatus.SERVICE_UNAVAILABLE
            : HttpStatus.FORBIDDEN;
    throw new PlatformProblemException(
        status, code, "The service is not authorized to curate Directory source facts.");
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

  private static DirectorySourceProjectionImportResult result(
      DirectorySourceProjectionReconciliation.Outcome outcome,
      DirectoryConceptType conceptType,
      InternalId conceptId) {
    return new DirectorySourceProjectionImportResult(
        outcome,
        Optional.ofNullable(conceptId).map(ignored -> conceptType),
        Optional.ofNullable(conceptId));
  }

  private static PlatformProblemException ownerFactMissing() {
    return new PlatformProblemException(
        HttpStatus.CONFLICT,
        PlatformErrorCode.DIRECTORY_UNAVAILABLE,
        "The source projection does not match an available Directory owner fact.");
  }

  private static PlatformProblemException versionConflict() {
    return new PlatformProblemException(
        HttpStatus.CONFLICT,
        PlatformErrorCode.DIRECTORY_VERSION_CONFLICT,
        "The Directory source projection changed concurrently; reconciliation must be retried.");
  }

  private record ResolvedLocation(Facility facility, DirectoryLocation location) {}
}
