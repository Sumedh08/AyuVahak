package org.ayuvahak.platform.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

    @NotNull @Positive private Integer anonymousRequestsPerMinute;

    @NotNull @Positive private Integer authenticatedReadRequestsPerMinute;

    @NotNull @Positive private Integer sensitiveWriteRequestsPerMinute;

    @NotNull @Positive private Integer adminRequestsPerMinute;

    @NotNull @Positive private Integer sseConnectionsPerPrincipal;

    @NotNull @Positive private Integer federationRequestsPerMinute;

    @NotNull @Positive private Integer exchangeRequestsPerMinute;

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
