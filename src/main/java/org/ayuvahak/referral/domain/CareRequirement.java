package org.ayuvahak.referral.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.ayuvahak.platform.core.ExternalReference;

/** Origin-asserted, typed care requirements; this value contains no capacity dimensions. */
public record CareRequirement(
    List<String> requiredCareCapabilityCodes,
    Optional<Instant> requestedStart,
    Optional<Instant> requestedEnd,
    List<String> permittedGeographyCodes,
    Optional<ExternalReference> selectedDestinationOrganization,
    Optional<String> priorityCode) {

  private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9._-]{0,63}");
  private static final int MAX_CAPABILITY_CODES = 20;
  private static final int MAX_GEOGRAPHY_CODES = 20;
  private static final int MAX_PRIORITY_LENGTH = 40;
  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";

  public CareRequirement {
    requiredCareCapabilityCodes =
        normalizedCodes(requiredCareCapabilityCodes, MAX_CAPABILITY_CODES);
    if (requiredCareCapabilityCodes.isEmpty()) {
      throw new IllegalArgumentException("at least one CareCapability code is required");
    }
    requestedStart = Objects.requireNonNull(requestedStart, "requested start");
    requestedEnd = Objects.requireNonNull(requestedEnd, "requested end");
    if (requestedStart.isPresent()
        && requestedEnd.isPresent()
        && !requestedStart.orElseThrow().isBefore(requestedEnd.orElseThrow())) {
      throw new IllegalArgumentException("requested start must be before requested end");
    }
    permittedGeographyCodes = normalizedCodes(permittedGeographyCodes, MAX_GEOGRAPHY_CODES);
    selectedDestinationOrganization =
        Objects.requireNonNull(selectedDestinationOrganization, "selected destination");
    selectedDestinationOrganization.ifPresent(
        reference -> {
          if (!DIRECTORY_ISSUER.equals(reference.issuer().value())
              || !"Organization".equals(reference.resourceType())) {
            throw new IllegalArgumentException(
                "selected destination must be a Directory Organization reference");
          }
        });
    priorityCode = Objects.requireNonNull(priorityCode, "priority code");
    priorityCode.ifPresent(
        code -> {
          if (code.length() > MAX_PRIORITY_LENGTH || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("priority code must be a bounded uppercase token");
          }
        });
  }

  private static List<String> normalizedCodes(List<String> codes, int maximum) {
    Objects.requireNonNull(codes, "codes");
    if (codes.size() > maximum) {
      throw new IllegalArgumentException("too many codes");
    }
    TreeSet<String> normalized = new TreeSet<>();
    for (String code : codes) {
      if (code == null || !CODE.matcher(code).matches() || !normalized.add(code)) {
        throw new IllegalArgumentException("codes must be unique uppercase tokens");
      }
    }
    return List.copyOf(normalized);
  }
}
