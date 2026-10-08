package org.ayuvahak.platform.audit.application;

import java.time.Instant;
import java.util.UUID;

/** Minimized audit chronology projection; it intentionally has no payload or credential fields. */
public record AuditRecordView(
    UUID id,
    String actionKind,
    String actorKind,
    String actorIssuer,
    String actorReference,
    String scopeKind,
    String organizationIssuer,
    String organizationReference,
    String facilityIssuer,
    String facilityReference,
    String actionCode,
    String targetType,
    UUID targetId,
    Instant occurredAt,
    Instant recordedAt,
    String outcome,
    String reasonCode,
    String authenticationEvidence,
    String authorizationOutcome,
    String correlationId,
    String targetScopeKind,
    String targetOrganizationIssuer,
    String targetOrganizationReference,
    String targetFacilityIssuer,
    String targetFacilityReference) {}
