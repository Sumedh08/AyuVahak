package org.ayuvahak.platform.configuration;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Optional documentation assistance is disabled unless an operator explicitly enables it. */
@ConfigurationProperties("ayuvahak.operator-support")
public class OperatorSupportProperties {

  private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
  private static final Pattern PROFILE_REVISION =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");
  private static final Pattern ENV_REFERENCE = Pattern.compile("env:[A-Za-z_][A-Za-z0-9_]{0,127}");
  private static final Pattern EVALUATION_FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

  private boolean enabled;
  private Provider provider = Provider.DETERMINISTIC_LOCAL;
  private URI endpoint;
  private String model = "";
  private String providerProfileRevision = "";
  private String secretReference = "";
  private String evaluatedProfileFingerprint = "";
  private List<String> allowedHosts = List.of();
  private Duration timeout = Duration.ofSeconds(8);
  private int maxResponseBytes = 16_384;
  private UseCase useCase = UseCase.UNASSESSED;
  private AllowedDataClass allowedDataClass = AllowedDataClass.UNASSESSED;
  private ProviderRetentionDisposition providerRetentionDisposition =
      ProviderRetentionDisposition.UNASSESSED;
  private ProviderTrainingUse providerTrainingUse = ProviderTrainingUse.UNASSESSED;
  private String processingJurisdiction = "";
  private BigDecimal providerMonthlySpendCap;
  private String providerMonthlySpendCapCurrency = "";
  private boolean providerSpendCapEnforced;
  private String providerSpendCapControlReference = "";

  public enum Provider {
    DETERMINISTIC_LOCAL,
    OPENAI_COMPATIBLE
  }

  public enum UseCase {
    DOCUMENTATION_OPERATOR_SUPPORT,
    UNASSESSED
  }

  public enum AllowedDataClass {
    APPROVED_DOCUMENTATION_ONLY,
    UNASSESSED
  }

  public enum ProviderRetentionDisposition {
    NO_PROMPT_RETENTION,
    DOCUMENTED_LIMITED_RETENTION,
    UNASSESSED
  }

  public enum ProviderTrainingUse {
    NO_TRAINING,
    TRAINING_ALLOWED,
    UNASSESSED
  }

  public UseCase getUseCase() {
    return useCase;
  }

  public void setUseCase(UseCase useCase) {
    this.useCase = Objects.requireNonNull(useCase, "useCase");
  }

  public AllowedDataClass getAllowedDataClass() {
    return allowedDataClass;
  }

  public void setAllowedDataClass(AllowedDataClass allowedDataClass) {
    this.allowedDataClass = Objects.requireNonNull(allowedDataClass, "allowedDataClass");
  }

  public ProviderRetentionDisposition getProviderRetentionDisposition() {
    return providerRetentionDisposition;
  }

  public void setProviderRetentionDisposition(ProviderRetentionDisposition value) {
    this.providerRetentionDisposition =
        Objects.requireNonNull(value, "providerRetentionDisposition");
  }

  public ProviderTrainingUse getProviderTrainingUse() {
    return providerTrainingUse;
  }

  public void setProviderTrainingUse(ProviderTrainingUse value) {
    this.providerTrainingUse = Objects.requireNonNull(value, "providerTrainingUse");
  }

  public String getProcessingJurisdiction() {
    return processingJurisdiction;
  }

  public void setProcessingJurisdiction(String value) {
    this.processingJurisdiction = value == null ? "" : value;
  }

  public BigDecimal getProviderMonthlySpendCap() {
    return providerMonthlySpendCap;
  }

  public void setProviderMonthlySpendCap(BigDecimal value) {
    this.providerMonthlySpendCap = value;
  }

  public String getProviderMonthlySpendCapCurrency() {
    return providerMonthlySpendCapCurrency;
  }

  public void setProviderMonthlySpendCapCurrency(String value) {
    this.providerMonthlySpendCapCurrency = value == null ? "" : value;
  }

  public boolean isProviderSpendCapEnforced() {
    return providerSpendCapEnforced;
  }

  public void setProviderSpendCapEnforced(boolean value) {
    this.providerSpendCapEnforced = value;
  }

  public String getProviderSpendCapControlReference() {
    return providerSpendCapControlReference;
  }

  public void setProviderSpendCapControlReference(String value) {
    this.providerSpendCapControlReference = value == null ? "" : value;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Provider getProvider() {
    return provider;
  }

  public void setProvider(Provider provider) {
    this.provider = Objects.requireNonNull(provider, "provider");
  }

  public URI getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(URI endpoint) {
    this.endpoint = endpoint;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model == null ? "" : model;
  }

  public String getSecretReference() {
    return secretReference;
  }

  public String getProviderProfileRevision() {
    return providerProfileRevision;
  }

  public void setProviderProfileRevision(String providerProfileRevision) {
    this.providerProfileRevision = providerProfileRevision == null ? "" : providerProfileRevision;
  }

  public String getEvaluatedProfileFingerprint() {
    return evaluatedProfileFingerprint;
  }

  public void setEvaluatedProfileFingerprint(String evaluatedProfileFingerprint) {
    this.evaluatedProfileFingerprint =
        evaluatedProfileFingerprint == null ? "" : evaluatedProfileFingerprint;
  }

  public void setSecretReference(String secretReference) {
    this.secretReference = secretReference == null ? "" : secretReference;
  }

  public List<String> getAllowedHosts() {
    return allowedHosts;
  }

  public void setAllowedHosts(List<String> allowedHosts) {
    this.allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
  }

  public Duration getTimeout() {
    return timeout;
  }

  public void setTimeout(Duration timeout) {
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  public int getMaxResponseBytes() {
    return maxResponseBytes;
  }

  public void setMaxResponseBytes(int maxResponseBytes) {
    this.maxResponseBytes = maxResponseBytes;
  }

  /** Validates only an active external profile; local fake and disabled modes need no endpoint. */
  public void validate() {
    if (!enabled || provider == Provider.DETERMINISTIC_LOCAL) {
      return;
    }
    if (provider != Provider.OPENAI_COMPATIBLE
        || endpoint == null
        || !"https".equalsIgnoreCase(endpoint.getScheme())
        || endpoint.getHost() == null
        || endpoint.getUserInfo() != null
        || endpoint.getQuery() != null
        || endpoint.getFragment() != null
        || (endpoint.getPort() != -1 && endpoint.getPort() != 443)
        || !"/v1/chat/completions".equals(endpoint.getPath())
        || !isPublicHostnameShape(endpoint.getHost())
        || allowedHosts.stream().noneMatch(host -> host.equalsIgnoreCase(endpoint.getHost()))
        || !MODEL.matcher(model).matches()
        || !PROFILE_REVISION.matcher(providerProfileRevision).matches()
        || !ENV_REFERENCE.matcher(secretReference).matches()
        || useCase != UseCase.DOCUMENTATION_OPERATOR_SUPPORT
        || allowedDataClass != AllowedDataClass.APPROVED_DOCUMENTATION_ONLY
        || (providerRetentionDisposition != ProviderRetentionDisposition.NO_PROMPT_RETENTION
            && providerRetentionDisposition
                != ProviderRetentionDisposition.DOCUMENTED_LIMITED_RETENTION)
        || providerTrainingUse != ProviderTrainingUse.NO_TRAINING
        || !processingJurisdiction.matches("[A-Z0-9][A-Z0-9-]{1,31}")
        || providerMonthlySpendCap == null
        || providerMonthlySpendCap.signum() <= 0
        || !providerMonthlySpendCapCurrency.matches("[A-Z]{3}")
        || !providerSpendCapEnforced
        || !providerSpendCapControlReference.matches("ref:[A-Za-z0-9._:/-]{1,120}")
        || timeout == null
        || timeout.compareTo(Duration.ofSeconds(1)) < 0
        || timeout.compareTo(Duration.ofSeconds(30)) > 0
        || maxResponseBytes < 1_024
        || maxResponseBytes > 65_536
        || (!evaluatedProfileFingerprint.isEmpty()
            && !EVALUATION_FINGERPRINT.matcher(evaluatedProfileFingerprint).matches())) {
      throw new IllegalArgumentException(
          "The configured operator support provider profile is invalid.");
    }
  }

  private static boolean isPublicHostnameShape(String host) {
    String normalized = host.toLowerCase(Locale.ROOT);
    if (normalized.equals("localhost")
        || normalized.endsWith(".localhost")
        || normalized.endsWith(".local")
        || normalized.endsWith(".internal")
        || normalized.endsWith(".test")
        || normalized.endsWith(".example")
        || normalized.endsWith(".invalid")
        || normalized.matches("[0-9.]+")
        || normalized.contains(":")
        || !normalized.matches(
            "(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+")) {
      return false;
    }
    // Do not resolve here. The send-time transport resolves once, vets the complete answer set,
    // pins the approved addresses and verifies the connected peer against that pinned set.
    return true;
  }
}
