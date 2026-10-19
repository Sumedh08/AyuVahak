package org.ayuvahak.referral.application;

import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.SourceKind;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.referral.domain.ReferralAuthorities;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Referral-owned application boundary for authorized aggregate reporting snapshots. */
@Service
public final class ReferralReportingSnapshots {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String ACTION = "REFERRAL_REPORTING_SNAPSHOT";
  private static final Set<String> APPROVED_MEASURES = Set.of("SUBMITTED_COUNT", "RESOLVED_COUNT");

  private final AuthorizationService authorization;
  private final ReferralReportingSnapshotStore store;
  private final AuditWriter auditWriter;
  private final Clock clock;

  public ReferralReportingSnapshots(
      AuthorizationService authorization,
      ReferralReportingSnapshotStore store,
      AuditWriter auditWriter,
      @Qualifier("auditClock") Clock clock) {
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.store = Objects.requireNonNull(store, "Referral reporting snapshot store");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  /** Returns a replay-stable owner snapshot for the validated organization or facility scope. */
  public ReferralReportingSnapshot snapshot(
      ExecutionContext context, ReferralReportingSnapshotRequest request) {
    Objects.requireNonNull(request, "reporting snapshot request");
    ReportingScope scope = requireReportingScope(context);
    if (context.actor().kind() != ActorKind.SERVICE || !isServiceExecution(context)) {
      deny(context, AuditAuthorizationOutcome.NOT_EVALUATED, "SERVICE_IDENTITY_REQUIRED");
      throw denied();
    }

    try {
      validate(request);
    } catch (ReferralReportingSnapshotException invalid) {
      throw new PlatformProblemException(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.MALFORMED_REQUEST,
          "Referral reporting snapshot inputs are invalid or unsupported.");
    }
    AuthorizationDecision decision =
        authorization.evaluate(context, ReferralAuthorities.reportingSnapshot().code());
    if (!decision.isAllowed()) {
      boolean unavailable =
          decision.denialReason().orElse(null)
              == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE;
      deny(
          context,
          AuditAuthorizationOutcome.DENIED,
          unavailable ? "AUTHORIZATION_STORE_UNAVAILABLE" : "AUTHORIZATION_DENIED");
      if (unavailable) {
        throw unavailable(
            PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
            "Referral could not authorize the reporting request safely.");
      }
      throw denied();
    }

    UUID organizationId = parseIdentifier(scope.organization());
    Optional<UUID> facilityId = scope.facility().map(ReferralReportingSnapshots::parseIdentifier);
    String fingerprint = fingerprint(scope.organization(), scope.facility(), request);
    ReferralReportingSnapshot snapshot;
    try {
      snapshot =
          store.getOrCreate(organizationId, facilityId, request, fingerprint, clock.instant());
    } catch (ReferralReportingSnapshotException conflict) {
      deny(context, AuditAuthorizationOutcome.ALLOWED, "REQUEST_ID_CONFLICT");
      throw new PlatformProblemException(
          HttpStatus.CONFLICT,
          PlatformErrorCode.REFERRAL_STATE_CONFLICT,
          "Referral reporting request identity conflicts with its prior inputs.");
    } catch (DataAccessException unavailable) {
      recordFailure(context, "REFERRAL_REPORTING_STORE_UNAVAILABLE");
      throw unavailable(
          PlatformErrorCode.REFERRAL_UNAVAILABLE,
          "Referral could not produce the requested reporting snapshot safely.");
    }

    recordSuccess(context);
    return snapshot;
  }

  private ReportingScope requireReportingScope(ExecutionContext context) {
    if (context != null && context.scope().organization().isPresent()) {
      ExternalReference organization = context.scope().organization().orElseThrow();
      Optional<ExternalReference> facility = context.scope().facility();
      boolean validOrganization =
          organization.issuer().value().equals(DIRECTORY_ISSUER)
              && organization.resourceType().equals("Organization");
      boolean validFacility =
          facility.isEmpty()
              || (context.scope().kind() == ExecutionScope.Kind.FACILITY
                  && facility.orElseThrow().issuer().value().equals(DIRECTORY_ISSUER)
                  && facility.orElseThrow().resourceType().equals("Facility"));
      boolean validKind =
          context.scope().kind() == ExecutionScope.Kind.ORGANIZATION && facility.isEmpty()
              || context.scope().kind() == ExecutionScope.Kind.FACILITY && facility.isPresent();
      if (validOrganization && validFacility && validKind) {
        return new ReportingScope(organization, facility);
      }
    }
    if (context != null) {
      deny(context, AuditAuthorizationOutcome.NOT_EVALUATED, "INVALID_EXECUTION_SCOPE");
    }
    throw denied();
  }

  private static boolean isServiceExecution(ExecutionContext context) {
    return context.sourceKind() == SourceKind.INTEGRATION
        || context.sourceKind() == SourceKind.BACKGROUND_WORK
        || context.sourceKind() == SourceKind.SCHEDULED_WORK;
  }

  private void validate(ReferralReportingSnapshotRequest request) {
    if (request.measureKeys().isEmpty()
        || !APPROVED_MEASURES.containsAll(request.measureKeys())
        || !request.periodStart().isBefore(request.periodEnd())
        || request.sourceCutoff().isAfter(clock.instant())
        || !hasMicrosecondPrecision(request.periodStart())
        || !hasMicrosecondPrecision(request.periodEnd())
        || !hasMicrosecondPrecision(request.sourceCutoff())) {
      throw new ReferralReportingSnapshotException(
          ReferralReportingSnapshotException.Reason.INVALID_REQUEST);
    }
  }

  private static boolean hasMicrosecondPrecision(java.time.Instant value) {
    return value.getNano() % 1_000 == 0;
  }

  private static UUID parseIdentifier(ExternalReference reference) {
    try {
      UUID parsed = UUID.fromString(reference.value());
      if (!parsed.toString().equalsIgnoreCase(reference.value())) {
        throw new IllegalArgumentException("non-canonical identifier");
      }
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.AUTHORIZATION_DENIED,
          "Referral could not authorize the reporting scope safely.");
    }
  }

  private static String fingerprint(
      ExternalReference organization,
      Optional<ExternalReference> facility,
      ReferralReportingSnapshotRequest request) {
    String canonical =
        organization.issuer().value()
            + "\nOrganization\n"
            + organization.value()
            + facility
                .map(
                    value ->
                        "\nFacility\n"
                            + value.issuer().value()
                            + "\n"
                            + value.resourceType()
                            + "\n"
                            + value.value())
                .orElse("")
            + "\n"
            + request.measureKeys().stream()
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElseThrow()
            + "\n"
            + request.periodStart()
            + "\n"
            + request.periodEnd()
            + "\n"
            + request.sourceCutoff();
    try {
      byte[] digest =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime.", impossible);
    }
  }

  private void recordSuccess(ExecutionContext context) {
    record(
        context,
        AuditActionKind.SENSITIVE_READ,
        AuditOutcome.SUCCEEDED,
        AuditAuthorizationOutcome.ALLOWED,
        Optional.empty());
  }

  private void recordFailure(ExecutionContext context, String reason) {
    record(
        context,
        AuditActionKind.SECURITY_OUTCOME,
        AuditOutcome.FAILED,
        AuditAuthorizationOutcome.ALLOWED,
        Optional.of(reason));
  }

  private void deny(
      ExecutionContext context, AuditAuthorizationOutcome authorizationOutcome, String reason) {
    record(
        context,
        AuditActionKind.SECURITY_OUTCOME,
        AuditOutcome.DENIED,
        authorizationOutcome,
        Optional.of(reason));
  }

  private void record(
      ExecutionContext context,
      AuditActionKind actionKind,
      AuditOutcome outcome,
      AuditAuthorizationOutcome authorizationOutcome,
      Optional<String> reason) {
    try {
      auditWriter.recordNonMutating(
          new AuditEvidence(
              actionKind,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              ACTION,
              "Referral",
              Optional.empty(),
              clock.instant(),
              outcome,
              reason,
              authentication(context.authenticationMechanism()),
              authorizationOutcome,
              context.correlationId(),
              Optional.of(
                  new AuditTargetContext(
                      context.scope().kind(),
                      context.scope().organization(),
                      context.scope().facility()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw unavailable(
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Referral withheld the reporting snapshot because required access audit is unavailable.");
    }
  }

  private static AuditAuthenticationEvidence authentication(AuthenticationMechanism mechanism) {
    return switch (mechanism) {
      case HUMAN_ACCESS_TOKEN -> AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN;
      case SERVICE_CLIENT_CREDENTIALS -> AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
      case CONFIGURED_SERVICE_IDENTITY -> AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY;
      case LOCAL_OPERATOR_CONTROL -> AuditAuthenticationEvidence.LOCAL_OPERATOR_CONTROL;
    };
  }

  private static PlatformProblemException denied() {
    return new PlatformProblemException(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.AUTHORIZATION_DENIED,
        "Referral could not authorize the reporting request.");
  }

  private static PlatformProblemException unavailable(PlatformErrorCode code, String message) {
    return new PlatformProblemException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
  }

  private record ReportingScope(
      ExternalReference organization, Optional<ExternalReference> facility) {}
}
