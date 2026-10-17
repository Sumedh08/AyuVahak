package org.ayuvahak.platform.exchange.domain;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;

/** Validated, immutable technical trust settings for one logical partner configuration version. */
public record PartnerTrustConfiguration(
    PartnerKey partnerKey,
    ExternalReference organization,
    String endpointOrigin,
    String basePath,
    Authentication authentication,
    TlsPolicy tlsPolicy,
    Optional<String> tlsTrustReference,
    Limits limits,
    RetryClass retryClass,
    List<AllowedOperation> allowedOperations,
    Optional<ActorReference> inboundPrincipal,
    Optional<FederationPeerConfiguration> federationPeer) {

  private static final Pattern SAFE_REFERENCE =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,255}");
  private static final Pattern SAFE_PROFILE =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,255}");

  public PartnerTrustConfiguration {
    Objects.requireNonNull(partnerKey, "partnerKey");
    Objects.requireNonNull(organization, "organization");
    if (!"Organization".equals(organization.resourceType())) {
      throw new IllegalArgumentException("partner organization must be an Organization reference");
    }
    endpointOrigin = normalizeHttpsOrigin(endpointOrigin, "partner endpoint origin");
    basePath = normalizePathPrefix(basePath, "partner base path");
    Objects.requireNonNull(authentication, "authentication");
    Objects.requireNonNull(tlsPolicy, "tlsPolicy");
    tlsTrustReference = Objects.requireNonNull(tlsTrustReference, "tlsTrustReference");
    tlsTrustReference.ifPresent(reference -> validateReference(reference, "TLS trust reference"));
    if ((tlsPolicy == TlsPolicy.PARTNER_CA) != tlsTrustReference.isPresent()) {
      throw new IllegalArgumentException(
          "partner CA policy requires exactly one TLS trust reference");
    }
    Objects.requireNonNull(limits, "limits");
    Objects.requireNonNull(retryClass, "retryClass");
    inboundPrincipal = Objects.requireNonNull(inboundPrincipal, "inboundPrincipal");
    federationPeer = Objects.requireNonNull(federationPeer, "federationPeer");
    allowedOperations =
        Objects.requireNonNull(allowedOperations, "allowedOperations").stream()
            .sorted(
                java.util.Comparator.comparing(
                        (AllowedOperation operation) -> operation.ownerContext().name())
                    .thenComparing(AllowedOperation::operationCode)
                    .thenComparing(operation -> operation.direction().name())
                    .thenComparing(AllowedOperation::profile))
            .toList();
    if (allowedOperations.isEmpty() || allowedOperations.size() > 20) {
      throw new IllegalArgumentException(
          "one to twenty explicitly allowed operations are required");
    }
    if (new HashSet<>(allowedOperations).size() != allowedOperations.size()) {
      throw new IllegalArgumentException("allowed operations must not contain duplicates");
    }
    boolean hasInbound =
        allowedOperations.stream()
            .anyMatch(operation -> operation.direction() == Direction.INBOUND);
    if (hasInbound != inboundPrincipal.isPresent()) {
      throw new IllegalArgumentException(
          "inbound operations require exactly one configured service principal");
    }
    inboundPrincipal.ifPresent(
        principal -> {
          if (principal.kind() != ActorKind.SERVICE) {
            throw new IllegalArgumentException("inbound partner principal must be a service actor");
          }
        });
    if (hasInbound && (retryClass != RetryClass.NEVER || limits.maxAttempts() != 1)) {
      throw new IllegalArgumentException("inbound operations must not be automatically retried");
    }
    if (retryClass == RetryClass.IDEMPOTENT_READ
        && allowedOperations.stream()
            .anyMatch(operation -> operation.direction() != Direction.OUTBOUND)) {
      throw new IllegalArgumentException("automatic retries are limited to outbound reads");
    }
    if (federationPeer.isPresent()) {
      FederationPeerConfiguration peer = federationPeer.orElseThrow();
      if (!peer.organization().equals(organization)) {
        throw new IllegalArgumentException(
            "federation peer organization must match the partner organization");
      }
      if (authentication.method() == AuthenticationMethod.NONE) {
        throw new IllegalArgumentException(
            "federation peer profiles require authenticated partner trust");
      }
      if (retryClass != RetryClass.NEVER || limits.maxAttempts() != 1) {
        throw new IllegalArgumentException(
            "federated operations require owner-controlled retry with one exchange attempt");
      }
      if (allowedOperations.stream()
          .anyMatch(
              operation ->
                  operation.ownerContext() == OwnerContext.REFERRAL
                      || operation.ownerContext() == OwnerContext.TRANSPORT
                      || operation.protocol() != Protocol.JSON
                      || !peer.protocolProfile().equals(operation.profile()))) {
        throw new IllegalArgumentException(
            "federation peer profiles allow only scoped JSON owner operations");
      }
    }
    if (allowedOperations.stream()
            .anyMatch(operation -> operation.ownerContext() == OwnerContext.TRANSPORT)
        && retryClass != RetryClass.NEVER) {
      throw new IllegalArgumentException(
          "Transport provider operations must not be automatically retried by Exchange");
    }
  }

  /** Compatibility constructor for profiles without federation peer identity. */
  public PartnerTrustConfiguration(
      PartnerKey partnerKey,
      ExternalReference organization,
      String endpointOrigin,
      String basePath,
      Authentication authentication,
      TlsPolicy tlsPolicy,
      Optional<String> tlsTrustReference,
      Limits limits,
      RetryClass retryClass,
      List<AllowedOperation> allowedOperations,
      Optional<ActorReference> inboundPrincipal) {
    this(
        partnerKey,
        organization,
        endpointOrigin,
        basePath,
        authentication,
        tlsPolicy,
        tlsTrustReference,
        limits,
        retryClass,
        allowedOperations,
        inboundPrincipal,
        Optional.empty());
  }

  /** Compatibility constructor for outbound-only profiles. */
  public PartnerTrustConfiguration(
      PartnerKey partnerKey,
      ExternalReference organization,
      String endpointOrigin,
      String basePath,
      Authentication authentication,
      TlsPolicy tlsPolicy,
      Optional<String> tlsTrustReference,
      Limits limits,
      RetryClass retryClass,
      List<AllowedOperation> allowedOperations) {
    this(
        partnerKey,
        organization,
        endpointOrigin,
        basePath,
        authentication,
        tlsPolicy,
        tlsTrustReference,
        limits,
        retryClass,
        allowedOperations,
        Optional.empty(),
        Optional.empty());
  }

  /** Applies canonical origin validation before a URL can enter trusted configuration. */
  public static String normalizeHttpsOrigin(String value, String label) {
    if (value == null || value.isBlank() || !value.equals(value.strip())) {
      throw new IllegalArgumentException(label + " is required and must be trimmed");
    }
    try {
      URI uri = new URI(value);
      String host = uri.getHost();
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || host == null
          || host.isBlank()
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || (uri.getRawPath() != null
              && !uri.getRawPath().isEmpty()
              && !"/".equals(uri.getRawPath()))) {
        throw new IllegalArgumentException(
            label + " must be an HTTPS origin without path or credentials");
      }
      String normalizedHost =
          host.indexOf(':') >= 0
              ? host.toLowerCase(Locale.ROOT)
              : IDN.toASCII(host).toLowerCase(Locale.ROOT);
      if (normalizedHost.endsWith(".") || normalizedHost.contains("%")) {
        throw new IllegalArgumentException(label + " must use a canonical hostname");
      }
      int port = uri.getPort();
      if (port < -1 || port == 0 || port > 65535) {
        throw new IllegalArgumentException(label + " has an invalid port");
      }
      return "https://"
          + bracketIpv6(normalizedHost)
          + (port == -1 || port == 443 ? "" : ":" + port);
    } catch (URISyntaxException | IllegalArgumentException exception) {
      if (exception instanceof IllegalArgumentException illegal
          && illegal.getMessage() != null
          && illegal.getMessage().startsWith(label)) {
        throw illegal;
      }
      throw new IllegalArgumentException(label + " is not a valid HTTPS origin");
    }
  }

  private static String normalizePathPrefix(String value, String label) {
    if (value == null
        || value.isBlank()
        || !value.startsWith("/")
        || value.length() > 512
        || value.contains("\\")
        || value.contains("?")
        || value.contains("#")
        || value.contains("//")
        || value.codePoints().anyMatch(Character::isISOControl)
        || value.toLowerCase(Locale.ROOT).matches(".*%(2e|2f|5c).*")) {
      throw new IllegalArgumentException(
          label + " must be a bounded normalized absolute path prefix");
    }
    for (String segment : value.split("/", -1)) {
      if (".".equals(segment) || "..".equals(segment)) {
        throw new IllegalArgumentException(label + " must not contain dot segments");
      }
    }
    if (!value.matches("/[A-Za-z0-9._~!$&'()*+,;=:@%/-]*")) {
      throw new IllegalArgumentException(label + " contains unsupported path characters");
    }
    return value.length() > 1 && value.endsWith("/")
        ? value.substring(0, value.length() - 1)
        : value;
  }

  private static String bracketIpv6(String host) {
    return host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
  }

  private static void validateReference(String value, String label) {
    if (value == null || !SAFE_REFERENCE.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " must be a bounded non-secret reference");
    }
  }

  public enum Direction {
    INBOUND,
    OUTBOUND
  }

  public enum OwnerContext {
    DIRECTORY,
    REFERRAL,
    TRANSPORT,
    CAPACITY,
    MATCHING,
    TRANSFER
  }

  public enum Protocol {
    FHIR_R4_JSON,
    JSON
  }

  public enum AuthenticationMethod {
    NONE,
    OAUTH_CLIENT_CREDENTIALS,
    OAUTH_PRIVATE_KEY_JWT,
    MUTUAL_TLS
  }

  public enum TlsPolicy {
    SYSTEM_CA,
    PARTNER_CA
  }

  public enum RetryClass {
    NEVER,
    IDEMPOTENT_READ
  }

  public record AllowedOperation(
      OwnerContext ownerContext,
      String operationCode,
      Direction direction,
      Protocol protocol,
      String profile) {

    public AllowedOperation {
      Objects.requireNonNull(ownerContext, "ownerContext");
      if (operationCode == null || !operationCode.matches("[a-z][a-z0-9.-]{1,63}")) {
        throw new IllegalArgumentException("operation code must be a stable lowercase identifier");
      }
      Objects.requireNonNull(direction, "direction");
      Objects.requireNonNull(protocol, "protocol");
      if ((ownerContext == OwnerContext.TRANSPORT
              || ownerContext == OwnerContext.CAPACITY
              || ownerContext == OwnerContext.MATCHING
              || ownerContext == OwnerContext.TRANSFER)
          && protocol != Protocol.JSON) {
        throw new IllegalArgumentException(
            "Transport and federation owner operations use bounded JSON");
      }
      if (ownerContext == OwnerContext.REFERRAL && protocol != Protocol.FHIR_R4_JSON) {
        throw new IllegalArgumentException("Referral clinical-context operations use FHIR R4 JSON");
      }
      if (profile == null || !SAFE_PROFILE.matcher(profile).matches()) {
        throw new IllegalArgumentException("profile must be a bounded identifier");
      }
    }

    public static AllowedOperation directoryIti130Feed() {
      return new AllowedOperation(
          OwnerContext.DIRECTORY,
          "mcsd.iti130.feed",
          Direction.INBOUND,
          Protocol.FHIR_R4_JSON,
          "IHE-mCSD");
    }

    /** Named partner-authenticated input for provider-neutral Transport observations. */
    public static AllowedOperation transportProviderEvidence() {
      return new AllowedOperation(
          OwnerContext.TRANSPORT,
          "transport.provider.evidence",
          Direction.INBOUND,
          Protocol.JSON,
          "AyuVahakTransportProvider-v1");
    }

    /** Named partner-authenticated input for cancellation and destination-change outcomes. */
    public static AllowedOperation transportProviderOutcomes() {
      return new AllowedOperation(
          OwnerContext.TRANSPORT,
          "transport.provider.outcomes",
          Direction.INBOUND,
          Protocol.JSON,
          "AyuVahakTransportProvider-v1");
    }

    public static AllowedOperation transportDispatch() {
      return transportOutbound("transport.dispatch");
    }

    public static AllowedOperation transportCancellation() {
      return transportOutbound("transport.request-cancellation");
    }

    public static AllowedOperation transportDestinationChange() {
      return transportOutbound("transport.destination-change");
    }

    /** Named federated movement fact operation received by the remote Transfer owner. */
    public static AllowedOperation transferMovementFact() {
      return transferMovementFact(Direction.OUTBOUND);
    }

    /** The same named operation as received by the remote Transfer owner. */
    public static AllowedOperation transferMovementFact(Direction direction) {
      return new AllowedOperation(
          OwnerContext.TRANSFER,
          "transfer.movement-fact",
          Objects.requireNonNull(direction, "direction"),
          Protocol.JSON,
          FederationPeerConfiguration.supportedProtocolProfile());
    }

    public static AllowedOperation directoryRemoteDiscovery(Direction direction) {
      return federationOperation(OwnerContext.DIRECTORY, "directory.remote-discovery", direction);
    }

    public static AllowedOperation capacityRemoteEvidence(Direction direction) {
      return federationOperation(OwnerContext.CAPACITY, "capacity.remote-evidence", direction);
    }

    public static AllowedOperation transferRemoteReceivingRequest(Direction direction) {
      return federationOperation(
          OwnerContext.TRANSFER, "transfer.remote-receiving-request", direction);
    }

    public static AllowedOperation transferRemoteReservationStatus(Direction direction) {
      return federationOperation(
          OwnerContext.TRANSFER, "transfer.remote-reservation-status", direction);
    }

    private static AllowedOperation federationOperation(
        OwnerContext ownerContext, String operationCode, Direction direction) {
      return new AllowedOperation(
          ownerContext,
          operationCode,
          Objects.requireNonNull(direction, "direction"),
          Protocol.JSON,
          FederationPeerConfiguration.supportedProtocolProfile());
    }

    private static AllowedOperation transportOutbound(String operationCode) {
      return new AllowedOperation(
          OwnerContext.TRANSPORT,
          operationCode,
          Direction.OUTBOUND,
          Protocol.JSON,
          "AyuVahakTransportProvider-v1");
    }
  }

  public record Authentication(
      AuthenticationMethod method,
      Optional<String> credentialReference,
      Optional<String> tokenSigningKeysReference,
      Optional<String> tokenOrigin,
      Optional<String> tokenPath,
      Optional<String> issuer,
      Optional<String> audience,
      Optional<String> clientId) {

    public Authentication {
      Objects.requireNonNull(method, "method");
      credentialReference = Objects.requireNonNull(credentialReference, "credentialReference");
      tokenSigningKeysReference =
          Objects.requireNonNull(tokenSigningKeysReference, "tokenSigningKeysReference");
      tokenOrigin = Objects.requireNonNull(tokenOrigin, "tokenOrigin");
      tokenPath = Objects.requireNonNull(tokenPath, "tokenPath");
      issuer = Objects.requireNonNull(issuer, "issuer");
      audience = Objects.requireNonNull(audience, "audience");
      clientId = Objects.requireNonNull(clientId, "clientId");
      credentialReference.ifPresent(
          reference -> validateReference(reference, "credential reference"));
      tokenSigningKeysReference.ifPresent(
          reference -> validateReference(reference, "token signing keys reference"));
      switch (method) {
        case NONE -> {
          if (credentialReference.isPresent()
              || tokenSigningKeysReference.isPresent()
              || tokenOrigin.isPresent()
              || tokenPath.isPresent()
              || issuer.isPresent()
              || audience.isPresent()
              || clientId.isPresent()) {
            throw new IllegalArgumentException(
                "unauthenticated profile must not include credentials");
          }
        }
        case OAUTH_CLIENT_CREDENTIALS, OAUTH_PRIVATE_KEY_JWT -> {
          if (credentialReference.isEmpty()
              || tokenSigningKeysReference.isEmpty()
              || tokenOrigin.isEmpty()
              || tokenPath.isEmpty()
              || issuer.isEmpty()
              || audience.isEmpty()
              || clientId.isEmpty()) {
            throw new IllegalArgumentException(
                "OAuth profile requires credential and trusted token key references");
          }
          tokenOrigin =
              Optional.of(normalizeHttpsOrigin(tokenOrigin.orElseThrow(), "OAuth token origin"));
          tokenPath = Optional.of(normalizePathPrefix(tokenPath.orElseThrow(), "OAuth token path"));
          issuer = Optional.of(requireSafeText(issuer.orElseThrow(), "OAuth issuer"));
          audience = Optional.of(requireSafeText(audience.orElseThrow(), "OAuth audience"));
          clientId =
              Optional.of(requireSafeText(clientId.orElseThrow(), "OAuth client identifier"));
        }
        case MUTUAL_TLS -> {
          if (credentialReference.isEmpty()
              || tokenSigningKeysReference.isPresent()
              || tokenOrigin.isPresent()
              || tokenPath.isPresent()
              || issuer.isPresent()
              || audience.isPresent()
              || clientId.isPresent()) {
            throw new IllegalArgumentException(
                "mutual TLS profile requires only a certificate/key reference");
          }
        }
      }
    }

    /** Compatibility constructor for unauthenticated and mutual-TLS profiles. */
    public Authentication(
        AuthenticationMethod method,
        Optional<String> credentialReference,
        Optional<String> tokenOrigin,
        Optional<String> tokenPath,
        Optional<String> issuer,
        Optional<String> audience,
        Optional<String> clientId) {
      this(
          method,
          credentialReference,
          Optional.empty(),
          tokenOrigin,
          tokenPath,
          issuer,
          audience,
          clientId);
    }

    public static Authentication none() {
      return new Authentication(
          AuthenticationMethod.NONE,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty());
    }

    private static String requireSafeText(String value, String label) {
      if (value == null
          || value.isBlank()
          || !value.equals(value.strip())
          || value.length() > 512
          || value.codePoints().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException(label + " must be bounded, nonblank, and trimmed");
      }
      return value;
    }

    @Override
    public String toString() {
      return "Authentication[method=" + method + ", references=redacted]";
    }
  }

  public record Limits(
      int connectTimeoutMillis,
      int responseTimeoutMillis,
      int operationTimeoutMillis,
      int maxResponseBytes,
      int maxDecompressedBytes,
      int maxPages,
      int maxResources,
      int maxNestingDepth,
      int maxAttempts) {

    public Limits {
      bounded(connectTimeoutMillis, 100, 120_000, "connect timeout");
      bounded(responseTimeoutMillis, 100, 120_000, "response timeout");
      bounded(operationTimeoutMillis, 100, 300_000, "operation timeout");
      bounded(maxResponseBytes, 1, 20_000_000, "response byte limit");
      bounded(maxDecompressedBytes, 1, 40_000_000, "decompressed byte limit");
      bounded(maxPages, 1, 100, "page limit");
      bounded(maxResources, 1, 10_000, "resource limit");
      bounded(maxNestingDepth, 1, 64, "nesting depth limit");
      bounded(maxAttempts, 1, 10, "attempt limit");
    }

    private static void bounded(int value, int minimum, int maximum, String label) {
      if (value < minimum || value > maximum) {
        throw new IllegalArgumentException(label + " is outside the allowed bounds");
      }
    }
  }

  @Override
  public String toString() {
    return "PartnerTrustConfiguration[partnerKey="
        + partnerKey
        + ", endpoint=redacted, authentication="
        + authentication
        + ", allowedOperations="
        + allowedOperations.size()
        + "]";
  }
}
