package org.ayuvahak.platform.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Explicit deployment-owned inputs required by either portable production profile. */
@Validated
@ConfigurationProperties("ayuvahak.production")
public class ProductionProfileProperties {

  @NotNull private Profile profile;

  @NotBlank private String publicOrigin;

  @Valid @NotNull private Tls tls = new Tls();

  @Valid @NotNull private Cors cors = new Cors();

  @Valid @NotNull private Limits limits = new Limits();

  @Valid @NotNull private Retention retention = new Retention();

  @Valid @NotNull private Secrets secrets = new Secrets();

  public Profile getProfile() {
    return profile;
  }

  public void setProfile(Profile profile) {
    this.profile = profile;
  }

  public String getPublicOrigin() {
    return publicOrigin;
  }

  public void setPublicOrigin(String publicOrigin) {
    this.publicOrigin = publicOrigin;
  }

  public Tls getTls() {
    return tls;
  }

  public void setTls(Tls tls) {
    this.tls = tls;
  }

  public Cors getCors() {
    return cors;
  }

  public void setCors(Cors cors) {
    this.cors = cors;
  }

  public Limits getLimits() {
    return limits;
  }

  public void setLimits(Limits limits) {
    this.limits = limits;
  }

  public Retention getRetention() {
    return retention;
  }

  public void setRetention(Retention retention) {
    this.retention = retention;
  }

  public Secrets getSecrets() {
    return secrets;
  }

  public void setSecrets(Secrets secrets) {
    this.secrets = secrets;
  }

  public enum Profile {
    SINGLE_SITE,
    HIGH_AVAILABILITY
  }

  public enum TlsTermination {
    REVERSE_PROXY
  }

  public static class Tls {

    @NotNull private TlsTermination termination;

    public TlsTermination getTermination() {
      return termination;
    }

    public void setTermination(TlsTermination termination) {
      this.termination = termination;
    }
  }

  public static class Cors {

    private boolean enabled;

    private List<String> allowedOrigins = new ArrayList<>();

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public List<String> getAllowedOrigins() {
      return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
      this.allowedOrigins =
          allowedOrigins == null ? new ArrayList<>() : new ArrayList<>(allowedOrigins);
    }
  }

  public static class Limits {

    @NotNull @Positive private Integer principalRequestsPerMinute;

    @NotNull @Positive private Integer scopeRequestsPerMinute;

    @NotNull @Positive private Integer partnerRequestsPerMinute;

    @NotNull @Positive private Integer anonymousRequestsPerMinute;

    @NotNull @Positive private Integer authenticatedReadRequestsPerMinute;

    @NotNull @Positive private Integer operatorSupportRequestsPerMinute;

    @NotNull @Positive private Integer sensitiveWriteRequestsPerMinute;

    @NotNull @Positive private Integer adminRequestsPerMinute;

    @NotNull @Positive private Integer sseConnectionsPerPrincipal;

    @NotNull @Positive private Integer sseConnectionsTotal;

    @NotNull @Positive private Integer sseSubscriptionsPerMinute;

    @NotNull @Positive private Integer federationRequestsPerMinute;

    @NotNull @Positive private Integer exchangeRequestsPerMinute;

    public Integer getPrincipalRequestsPerMinute() {
      return principalRequestsPerMinute;
    }

    public void setPrincipalRequestsPerMinute(Integer value) {
      principalRequestsPerMinute = value;
    }

    public Integer getScopeRequestsPerMinute() {
      return scopeRequestsPerMinute;
    }

    public void setScopeRequestsPerMinute(Integer value) {
      scopeRequestsPerMinute = value;
    }

    public Integer getPartnerRequestsPerMinute() {
      return partnerRequestsPerMinute;
    }

    public void setPartnerRequestsPerMinute(Integer value) {
      partnerRequestsPerMinute = value;
    }

    public Integer getAnonymousRequestsPerMinute() {
      return anonymousRequestsPerMinute;
    }

    public void setAnonymousRequestsPerMinute(Integer value) {
      anonymousRequestsPerMinute = value;
    }

    public Integer getAuthenticatedReadRequestsPerMinute() {
      return authenticatedReadRequestsPerMinute;
    }

    public void setAuthenticatedReadRequestsPerMinute(Integer value) {
      authenticatedReadRequestsPerMinute = value;
    }

    public Integer getOperatorSupportRequestsPerMinute() {
      return operatorSupportRequestsPerMinute;
    }

    public void setOperatorSupportRequestsPerMinute(Integer value) {
      operatorSupportRequestsPerMinute = value;
    }

    public Integer getSensitiveWriteRequestsPerMinute() {
      return sensitiveWriteRequestsPerMinute;
    }

    public void setSensitiveWriteRequestsPerMinute(Integer value) {
      sensitiveWriteRequestsPerMinute = value;
    }

    public Integer getAdminRequestsPerMinute() {
      return adminRequestsPerMinute;
    }

    public void setAdminRequestsPerMinute(Integer value) {
      adminRequestsPerMinute = value;
    }

    public Integer getSseConnectionsPerPrincipal() {
      return sseConnectionsPerPrincipal;
    }

    public void setSseConnectionsPerPrincipal(Integer value) {
      sseConnectionsPerPrincipal = value;
    }

    public Integer getSseConnectionsTotal() {
      return sseConnectionsTotal;
    }

    public void setSseConnectionsTotal(Integer value) {
      sseConnectionsTotal = value;
    }

    public Integer getSseSubscriptionsPerMinute() {
      return sseSubscriptionsPerMinute;
    }

    public void setSseSubscriptionsPerMinute(Integer value) {
      sseSubscriptionsPerMinute = value;
    }

    public Integer getFederationRequestsPerMinute() {
      return federationRequestsPerMinute;
    }

    public void setFederationRequestsPerMinute(Integer value) {
      federationRequestsPerMinute = value;
    }

    public Integer getExchangeRequestsPerMinute() {
      return exchangeRequestsPerMinute;
    }

    public void setExchangeRequestsPerMinute(Integer value) {
      exchangeRequestsPerMinute = value;
    }
  }

  public static class Retention {

    @NotBlank private String scheduleId;

    @NotNull private LocalDate reviewedAt;

    @NotBlank private String accountableAuthority;

    @NotNull
    private Map<RetentionCategory, @Valid RetentionSchedule> register =
        new EnumMap<>(RetentionCategory.class);

    public String getScheduleId() {
      return scheduleId;
    }

    public void setScheduleId(String scheduleId) {
      this.scheduleId = scheduleId;
    }

    public LocalDate getReviewedAt() {
      return reviewedAt;
    }

    public void setReviewedAt(LocalDate reviewedAt) {
      this.reviewedAt = reviewedAt;
    }

    public String getAccountableAuthority() {
      return accountableAuthority;
    }

    public void setAccountableAuthority(String accountableAuthority) {
      this.accountableAuthority = accountableAuthority;
    }

    public Map<RetentionCategory, RetentionSchedule> getRegister() {
      return register;
    }

    public void setRegister(Map<RetentionCategory, RetentionSchedule> register) {
      EnumMap<RetentionCategory, RetentionSchedule> copy = new EnumMap<>(RetentionCategory.class);
      if (register != null) {
        copy.putAll(register);
      }
      this.register = copy;
    }

    @AssertTrue(
        message =
            "production retention register must cover every owner category with a complete schedule and hold, unresolved-work, and restore safeguards")
    public boolean isValidRegister() {
      if (scheduleId == null
          || scheduleId.isBlank()
          || reviewedAt == null
          || accountableAuthority == null
          || accountableAuthority.isBlank()
          || register == null
          || register.size() != RetentionCategory.values().length) {
        return false;
      }
      for (RetentionCategory category : RetentionCategory.values()) {
        RetentionSchedule schedule = register.get(category);
        if (schedule == null || !schedule.isValidFor(category)) {
          return false;
        }
      }
      return true;
    }
  }

  /** Categories correspond one-to-one with the frozen owner-by-owner retention matrix. */
  public enum RetentionCategory {
    PLATFORM_IDENTITY_GRANTS(RetentionOwner.PLATFORM),
    PLATFORM_AUDIT(RetentionOwner.PLATFORM),
    PLATFORM_IDEMPOTENCY(RetentionOwner.PLATFORM),
    PLATFORM_EVENT_OUTBOX(RetentionOwner.PLATFORM),
    PLATFORM_NOTIFICATION_PROJECTION(RetentionOwner.PLATFORM),
    DIRECTORY_PROJECTIONS(RetentionOwner.DIRECTORY),
    CAPACITY_EVIDENCE_AND_RESERVATIONS(RetentionOwner.CAPACITY),
    REFERRAL_CASES(RetentionOwner.REFERRAL),
    MATCHING_RECOMMENDATION_EVIDENCE(RetentionOwner.MATCHING),
    TRANSFER_HISTORY(RetentionOwner.TRANSFER),
    TRANSPORT_HISTORY(RetentionOwner.TRANSPORT),
    PHASE_9_EXCHANGE(RetentionOwner.EXCHANGE),
    PHASE_11_FEDERATION(RetentionOwner.FEDERATION),
    PHASE_12_ANALYTICS(RetentionOwner.ANALYTICS),
    LOGS_METRICS_AND_TRACES(RetentionOwner.OBSERVABILITY),
    BACKUPS_AND_RESTORE(RetentionOwner.BACKUP);

    private final RetentionOwner owner;

    RetentionCategory(RetentionOwner owner) {
      this.owner = owner;
    }

    public RetentionOwner owner() {
      return owner;
    }
  }

  public enum RetentionOwner {
    PLATFORM,
    DIRECTORY,
    CAPACITY,
    REFERRAL,
    MATCHING,
    TRANSFER,
    TRANSPORT,
    EXCHANGE,
    FEDERATION,
    ANALYTICS,
    OBSERVABILITY,
    BACKUP
  }

  public enum RetentionExpiryAction {
    PURGE,
    MINIMIZE_OR_PSEUDONYMIZE,
    EXPIRE_PROJECTION,
    RESTRICTED_TOMBSTONE,
    OWNER_REVIEW
  }

  /** Deployment-owned register entry; its owner performs the action in its own persistence. */
  public static class RetentionSchedule {

    @NotNull private RetentionOwner owner;

    @NotBlank private String purpose;

    @NotBlank private String dataClass;

    @NotBlank private String accessPolicy;

    @NotBlank private String lifecycleTrigger;

    @NotNull private Duration retentionPeriod;

    @NotBlank private String governanceBasis;

    @NotBlank private String holdReviewAuthority;

    @NotNull private RetentionExpiryAction expiryAction;

    @NotBlank private String evidenceDependencies;

    @NotBlank private String backupPropagation;

    @NotBlank private String verificationMethod;

    private boolean blockWhileHeld;

    private boolean blockWhileUnresolved;

    private boolean reapplyCompletedActionsBeforeServing;

    public RetentionOwner getOwner() {
      return owner;
    }

    public void setOwner(RetentionOwner owner) {
      this.owner = owner;
    }

    public String getPurpose() {
      return purpose;
    }

    public void setPurpose(String purpose) {
      this.purpose = purpose;
    }

    public String getDataClass() {
      return dataClass;
    }

    public void setDataClass(String dataClass) {
      this.dataClass = dataClass;
    }

    public String getAccessPolicy() {
      return accessPolicy;
    }

    public void setAccessPolicy(String accessPolicy) {
      this.accessPolicy = accessPolicy;
    }

    public String getLifecycleTrigger() {
      return lifecycleTrigger;
    }

    public void setLifecycleTrigger(String lifecycleTrigger) {
      this.lifecycleTrigger = lifecycleTrigger;
    }

    public Duration getRetentionPeriod() {
      return retentionPeriod;
    }

    public void setRetentionPeriod(Duration retentionPeriod) {
      this.retentionPeriod = retentionPeriod;
    }

    public String getGovernanceBasis() {
      return governanceBasis;
    }

    public void setGovernanceBasis(String governanceBasis) {
      this.governanceBasis = governanceBasis;
    }

    public String getHoldReviewAuthority() {
      return holdReviewAuthority;
    }

    public void setHoldReviewAuthority(String holdReviewAuthority) {
      this.holdReviewAuthority = holdReviewAuthority;
    }

    public RetentionExpiryAction getExpiryAction() {
      return expiryAction;
    }

    public void setExpiryAction(RetentionExpiryAction expiryAction) {
      this.expiryAction = expiryAction;
    }

    public String getEvidenceDependencies() {
      return evidenceDependencies;
    }

    public void setEvidenceDependencies(String evidenceDependencies) {
      this.evidenceDependencies = evidenceDependencies;
    }

    public String getBackupPropagation() {
      return backupPropagation;
    }

    public void setBackupPropagation(String backupPropagation) {
      this.backupPropagation = backupPropagation;
    }

    public String getVerificationMethod() {
      return verificationMethod;
    }

    public void setVerificationMethod(String verificationMethod) {
      this.verificationMethod = verificationMethod;
    }

    public boolean isBlockWhileHeld() {
      return blockWhileHeld;
    }

    public void setBlockWhileHeld(boolean blockWhileHeld) {
      this.blockWhileHeld = blockWhileHeld;
    }

    public boolean isBlockWhileUnresolved() {
      return blockWhileUnresolved;
    }

    public void setBlockWhileUnresolved(boolean blockWhileUnresolved) {
      this.blockWhileUnresolved = blockWhileUnresolved;
    }

    public boolean isReapplyCompletedActionsBeforeServing() {
      return reapplyCompletedActionsBeforeServing;
    }

    public void setReapplyCompletedActionsBeforeServing(
        boolean reapplyCompletedActionsBeforeServing) {
      this.reapplyCompletedActionsBeforeServing = reapplyCompletedActionsBeforeServing;
    }

    private boolean isValidFor(RetentionCategory category) {
      return category != null
          && owner == category.owner()
          && hasText(purpose)
          && hasText(dataClass)
          && hasText(accessPolicy)
          && hasText(lifecycleTrigger)
          && retentionPeriod != null
          && !retentionPeriod.isNegative()
          && !retentionPeriod.isZero()
          && hasText(governanceBasis)
          && hasText(holdReviewAuthority)
          && expiryAction != null
          && supportsConfiguredExpiryAction(category)
          && hasText(evidenceDependencies)
          && hasText(backupPropagation)
          && hasText(verificationMethod)
          && blockWhileHeld
          && blockWhileUnresolved
          && reapplyCompletedActionsBeforeServing;
    }

    private boolean supportsConfiguredExpiryAction(RetentionCategory category) {
      if (expiryAction == RetentionExpiryAction.OWNER_REVIEW) {
        return true;
      }
      return switch (category) {
        case PLATFORM_EVENT_OUTBOX -> expiryAction == RetentionExpiryAction.PURGE;
        case PLATFORM_NOTIFICATION_PROJECTION ->
            expiryAction == RetentionExpiryAction.EXPIRE_PROJECTION;
        default -> false;
      };
    }

    private static boolean hasText(String value) {
      return value != null && !value.isBlank();
    }
  }

  public static class Secrets {

    @NotBlank private String databasePasswordReference;

    @NotBlank private String tlsCertificateReference;

    public String getDatabasePasswordReference() {
      return databasePasswordReference;
    }

    public void setDatabasePasswordReference(String databasePasswordReference) {
      this.databasePasswordReference = databasePasswordReference;
    }

    public String getTlsCertificateReference() {
      return tlsCertificateReference;
    }

    public void setTlsCertificateReference(String tlsCertificateReference) {
      this.tlsCertificateReference = tlsCertificateReference;
    }
  }
}
