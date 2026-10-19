package org.ayuvahak.analytics.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.exchange.domain.PartnerKey;

/** Immutable administrator-approved mapping to pre-existing DHIS2 aggregate metadata. */
public record Dhis2MappingVersion(
    String mappingKey,
    int version,
    String indicatorKey,
    int indicatorVersion,
    ExternalReference organization,
    Optional<ExternalReference> facility,
    PartnerKey partnerKey,
    String dataElementUid,
    String orgUnitUid,
    Optional<String> dataSetUid,
    Optional<String> categoryOptionComboUid,
    Optional<String> attributeOptionComboUid,
    Dhis2PeriodFormat periodFormat,
    String disclosurePolicyKey,
    String disclosureOutputKey,
    boolean exclusiveWriteAuthority,
    boolean zeroIsSignificant,
    Instant effectiveFrom,
    Optional<Instant> effectiveUntil,
    String fingerprint) {

  private static final Pattern METADATA_IDENTIFIER =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  public Dhis2MappingVersion(
      String mappingKey,
      int version,
      String indicatorKey,
      int indicatorVersion,
      ExternalReference organization,
      PartnerKey partnerKey,
      String dataElementUid,
      String orgUnitUid,
      Optional<String> dataSetUid,
      Optional<String> categoryOptionComboUid,
      Optional<String> attributeOptionComboUid,
      Dhis2PeriodFormat periodFormat,
      String disclosurePolicyKey,
      String disclosureOutputKey,
      boolean exclusiveWriteAuthority,
      boolean zeroIsSignificant,
      Instant effectiveFrom,
      Optional<Instant> effectiveUntil) {
    this(
        mappingKey,
        version,
        indicatorKey,
        indicatorVersion,
        organization,
        Optional.empty(),
        partnerKey,
        dataElementUid,
        orgUnitUid,
        dataSetUid,
        categoryOptionComboUid,
        attributeOptionComboUid,
        periodFormat,
        disclosurePolicyKey,
        disclosureOutputKey,
        exclusiveWriteAuthority,
        zeroIsSignificant,
        effectiveFrom,
        effectiveUntil,
        "pending");
  }

  public Dhis2MappingVersion(
      String mappingKey,
      int version,
      String indicatorKey,
      int indicatorVersion,
      ExternalReference organization,
      Optional<ExternalReference> facility,
      PartnerKey partnerKey,
      String dataElementUid,
      String orgUnitUid,
      Optional<String> dataSetUid,
      Optional<String> categoryOptionComboUid,
      Optional<String> attributeOptionComboUid,
      Dhis2PeriodFormat periodFormat,
      String disclosurePolicyKey,
      String disclosureOutputKey,
      boolean exclusiveWriteAuthority,
      boolean zeroIsSignificant,
      Instant effectiveFrom,
      Optional<Instant> effectiveUntil) {
    this(
        mappingKey,
        version,
        indicatorKey,
        indicatorVersion,
        organization,
        facility,
        partnerKey,
        dataElementUid,
        orgUnitUid,
        dataSetUid,
        categoryOptionComboUid,
        attributeOptionComboUid,
        periodFormat,
        disclosurePolicyKey,
        disclosureOutputKey,
        exclusiveWriteAuthority,
        zeroIsSignificant,
        effectiveFrom,
        effectiveUntil,
        "pending");
  }

  public Dhis2MappingVersion {
    if (mappingKey == null || !mappingKey.matches("[A-Z0-9][A-Z0-9._:-]{0,63}")) {
      throw new IllegalArgumentException("DHIS2 mapping key is invalid");
    }
    if (version < 1 || indicatorVersion < 1) {
      throw new IllegalArgumentException("DHIS2 mapping and indicator versions must be positive");
    }
    if (indicatorKey == null || !indicatorKey.matches("[A-Z0-9][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("indicator key is invalid");
    }
    Objects.requireNonNull(organization, "organization");
    if (!"urn:ayuvahak:directory".equals(organization.issuer().value())
        || !"Organization".equals(organization.resourceType())) {
      throw new IllegalArgumentException("DHIS2 mapping organization must be Directory-owned");
    }
    facility = Objects.requireNonNull(facility, "facility");
    facility.ifPresent(
        reference -> {
          if (!"urn:ayuvahak:directory".equals(reference.issuer().value())
              || !"Facility".equals(reference.resourceType())) {
            throw new IllegalArgumentException("DHIS2 mapping facility must be Directory-owned");
          }
        });
    Objects.requireNonNull(partnerKey, "target partner key");
    requireMetadataIdentifier(dataElementUid, "data element UID");
    requireMetadataIdentifier(orgUnitUid, "organization unit UID");
    dataSetUid = Objects.requireNonNull(dataSetUid, "data set UID");
    categoryOptionComboUid =
        Objects.requireNonNull(categoryOptionComboUid, "category option combo");
    attributeOptionComboUid =
        Objects.requireNonNull(attributeOptionComboUid, "attribute option combo");
    dataSetUid.ifPresent(value -> requireMetadataIdentifier(value, "data set UID"));
    categoryOptionComboUid.ifPresent(
        value -> requireMetadataIdentifier(value, "category option combo UID"));
    attributeOptionComboUid.ifPresent(
        value -> requireMetadataIdentifier(value, "attribute option combo UID"));
    Objects.requireNonNull(periodFormat, "DHIS2 period format");
    if (disclosurePolicyKey == null || !disclosurePolicyKey.matches("[A-Z0-9][A-Z0-9._:-]{0,63}")) {
      throw new IllegalArgumentException("disclosure policy key is invalid");
    }
    if (disclosureOutputKey == null
        || !disclosureOutputKey.matches("[A-Z0-9][A-Z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("disclosure output key is invalid");
    }
    Objects.requireNonNull(effectiveFrom, "effective from");
    effectiveUntil = Objects.requireNonNull(effectiveUntil, "effective until");
    if (effectiveUntil.filter(until -> !effectiveFrom.isBefore(until)).isPresent()) {
      throw new IllegalArgumentException("DHIS2 mapping effective interval is empty");
    }
    String calculated =
        fingerprint(
            mappingKey,
            version,
            indicatorKey,
            indicatorVersion,
            organization,
            facility,
            partnerKey,
            dataElementUid,
            orgUnitUid,
            dataSetUid,
            categoryOptionComboUid,
            attributeOptionComboUid,
            periodFormat,
            disclosurePolicyKey,
            disclosureOutputKey,
            exclusiveWriteAuthority,
            zeroIsSignificant,
            effectiveFrom,
            effectiveUntil);
    if (fingerprint == null || fingerprint.equals("pending")) {
      fingerprint = calculated;
    } else if (!fingerprint.equals(calculated)) {
      throw new IllegalArgumentException("DHIS2 mapping fingerprint is inconsistent");
    }
  }

  public boolean effectiveAt(Instant instant) {
    Objects.requireNonNull(instant, "evaluation instant");
    return !instant.isBefore(effectiveFrom)
        && effectiveUntil.filter(until -> !instant.isBefore(until)).isEmpty();
  }

  public Dhis2DataValueTuple tupleFor(ReportingPeriod period) {
    Objects.requireNonNull(period, "reporting period");
    return new Dhis2DataValueTuple(
        dataElementUid,
        periodFormat.periodCode(period.startInclusiveLocal(), period.endExclusiveLocal()),
        orgUnitUid,
        categoryOptionComboUid,
        attributeOptionComboUid);
  }

  public static void requireMetadataIdentifier(String value, String label) {
    if (value == null || !METADATA_IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(
          label + " must be a bounded configured metadata identifier");
    }
  }

  private static String fingerprint(
      String mappingKey,
      int version,
      String indicatorKey,
      int indicatorVersion,
      ExternalReference organization,
      Optional<ExternalReference> facility,
      PartnerKey partnerKey,
      String dataElementUid,
      String orgUnitUid,
      Optional<String> dataSetUid,
      Optional<String> categoryOptionComboUid,
      Optional<String> attributeOptionComboUid,
      Dhis2PeriodFormat periodFormat,
      String disclosurePolicyKey,
      String disclosureOutputKey,
      boolean exclusiveWriteAuthority,
      boolean zeroIsSignificant,
      Instant effectiveFrom,
      Optional<Instant> effectiveUntil) {
    java.util.ArrayList<String> parts =
        new java.util.ArrayList<>(
            java.util.List.of(
                mappingKey,
                Integer.toString(version),
                indicatorKey,
                Integer.toString(indicatorVersion),
                organization.issuer().value(),
                organization.resourceType(),
                organization.value()));
    // Keep the historical organization-only fingerprint canonical form stable across upgrades.
    facility.ifPresent(value -> parts.add(value.issuer().value() + ":" + value.value()));
    parts.addAll(
        java.util.List.of(
            partnerKey.value(),
            dataElementUid,
            orgUnitUid,
            dataSetUid.orElse(""),
            categoryOptionComboUid.orElse(""),
            attributeOptionComboUid.orElse(""),
            periodFormat.name(),
            disclosurePolicyKey,
            disclosureOutputKey,
            Boolean.toString(exclusiveWriteAuthority),
            Boolean.toString(zeroIsSignificant),
            effectiveFrom.toString(),
            effectiveUntil.map(Instant::toString).orElse("")));
    String canonical = String.join("\n", parts);
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
    }
  }

  @Override
  public String toString() {
    return "Dhis2MappingVersion[mappingKey="
        + mappingKey
        + ", version="
        + version
        + ", indicatorKey="
        + indicatorKey
        + ", indicatorVersion="
        + indicatorVersion
        + ", partnerKey="
        + partnerKey
        + ", targetMetadata=redacted, fingerprint="
        + fingerprint
        + "]";
  }
}
