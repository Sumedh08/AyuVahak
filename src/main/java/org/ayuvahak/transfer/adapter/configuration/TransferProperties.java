package org.ayuvahak.transfer.adapter.configuration;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment policy for Transfer request deadlines and Capacity reservation recovery. */
@ConfigurationProperties(prefix = "ayuvahak.transfer")
public class TransferProperties {

  private Duration requestDeadline;
  private String expiryWorkerClientId = "ayuvahak-transfer-expiry-worker";
  private Duration expiryPollInterval = Duration.ofSeconds(15);
  private int expiryBatchSize = 50;
  private boolean expiryPollingEnabled = true;
  private String reservationWorkerClientId = "ayuvahak-transfer-reservation-worker";
  private String ownerEventConsumerClientId = "ayuvahak-transfer-owner-event-consumer";
  private String reservationDimension;
  private String reservationUnit;
  private BigDecimal reservationQuantity;
  private Duration reservationDuration;
  private Duration reservationPollInterval = Duration.ofSeconds(15);
  private Duration reservationAttemptLease = Duration.ofMinutes(1);
  private int reservationBatchSize = 50;
  private boolean reservationPollingEnabled = true;

  public Duration getRequestDeadline() {
    return requestDeadline;
  }

  public void setRequestDeadline(Duration requestDeadline) {
    this.requestDeadline = requestDeadline;
  }

  public String getExpiryWorkerClientId() {
    return expiryWorkerClientId;
  }

  public void setExpiryWorkerClientId(String expiryWorkerClientId) {
    this.expiryWorkerClientId = expiryWorkerClientId;
  }

  public Duration getExpiryPollInterval() {
    return expiryPollInterval;
  }

  public void setExpiryPollInterval(Duration expiryPollInterval) {
    this.expiryPollInterval = expiryPollInterval;
  }

  public int getExpiryBatchSize() {
    return expiryBatchSize;
  }

  public void setExpiryBatchSize(int expiryBatchSize) {
    this.expiryBatchSize = expiryBatchSize;
  }

  public boolean isExpiryPollingEnabled() {
    return expiryPollingEnabled;
  }

  public void setExpiryPollingEnabled(boolean expiryPollingEnabled) {
    this.expiryPollingEnabled = expiryPollingEnabled;
  }

  public String getReservationWorkerClientId() {
    return reservationWorkerClientId;
  }

  public String getOwnerEventConsumerClientId() {
    return ownerEventConsumerClientId;
  }

  public void setOwnerEventConsumerClientId(String clientId) {
    ownerEventConsumerClientId = clientId;
  }

  public void setReservationWorkerClientId(String reservationWorkerClientId) {
    this.reservationWorkerClientId = reservationWorkerClientId;
  }

  public String getReservationDimension() {
    return reservationDimension;
  }

  public void setReservationDimension(String reservationDimension) {
    this.reservationDimension = reservationDimension;
  }

  public String getReservationUnit() {
    return reservationUnit;
  }

  public void setReservationUnit(String reservationUnit) {
    this.reservationUnit = reservationUnit;
  }

  public BigDecimal getReservationQuantity() {
    return reservationQuantity;
  }

  public void setReservationQuantity(BigDecimal reservationQuantity) {
    this.reservationQuantity = reservationQuantity;
  }

  public Duration getReservationDuration() {
    return reservationDuration;
  }

  public void setReservationDuration(Duration reservationDuration) {
    this.reservationDuration = reservationDuration;
  }

  public Duration getReservationPollInterval() {
    return reservationPollInterval;
  }

  public void setReservationPollInterval(Duration reservationPollInterval) {
    this.reservationPollInterval = reservationPollInterval;
  }

  public Duration getReservationAttemptLease() {
    return reservationAttemptLease;
  }

  public void setReservationAttemptLease(Duration reservationAttemptLease) {
    this.reservationAttemptLease = reservationAttemptLease;
  }

  public int getReservationBatchSize() {
    return reservationBatchSize;
  }

  public void setReservationBatchSize(int reservationBatchSize) {
    this.reservationBatchSize = reservationBatchSize;
  }

  public boolean isReservationPollingEnabled() {
    return reservationPollingEnabled;
  }

  public void setReservationPollingEnabled(boolean reservationPollingEnabled) {
    this.reservationPollingEnabled = reservationPollingEnabled;
  }

  public Optional<ReservationPolicy> reservationPolicy() {
    if (reservationDimension == null) {
      return Optional.empty();
    }
    return Optional.of(
        new ReservationPolicy(
            reservationDimension, reservationUnit, reservationQuantity, reservationDuration));
  }

  @PostConstruct
  void validateExpiryConfiguration() {
    if (expiryWorkerClientId == null
        || !expiryWorkerClientId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("Transfer expiry worker client id is invalid");
    }
    if (expiryPollInterval == null
        || expiryPollInterval.isZero()
        || expiryPollInterval.isNegative()) {
      throw new IllegalArgumentException("Transfer expiry poll interval must be positive");
    }
    if (expiryBatchSize < 1 || expiryBatchSize > 500) {
      throw new IllegalArgumentException("Transfer expiry batch size must be between 1 and 500");
    }
    if (reservationWorkerClientId == null
        || !reservationWorkerClientId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("Transfer reservation worker client id is invalid");
    }
    if (ownerEventConsumerClientId == null
        || !ownerEventConsumerClientId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("Transfer owner-event consumer client id is invalid");
    }
    boolean anyReservationValue =
        reservationDimension != null
            || reservationUnit != null
            || reservationQuantity != null
            || reservationDuration != null;
    boolean completeReservationPolicy =
        reservationDimension != null
            && reservationDimension.matches("[A-Z][A-Z0-9._-]{0,63}")
            && reservationUnit != null
            && reservationUnit.matches("[A-Za-z][A-Za-z0-9._-]{0,31}")
            && reservationQuantity != null
            && reservationQuantity.signum() > 0
            && reservationDuration != null
            && !reservationDuration.isZero()
            && !reservationDuration.isNegative();
    if (anyReservationValue && !completeReservationPolicy) {
      throw new IllegalArgumentException(
          "Transfer reservation dimension, unit, positive quantity, and interval must be configured together");
    }
    if (reservationPollInterval == null
        || reservationPollInterval.isZero()
        || reservationPollInterval.isNegative()) {
      throw new IllegalArgumentException("Transfer reservation poll interval must be positive");
    }
    if (reservationAttemptLease == null
        || reservationAttemptLease.isZero()
        || reservationAttemptLease.isNegative()) {
      throw new IllegalArgumentException("Transfer reservation attempt lease must be positive");
    }
    if (reservationBatchSize < 1 || reservationBatchSize > 500) {
      throw new IllegalArgumentException(
          "Transfer reservation batch size must be between 1 and 500");
    }
  }

  public record ReservationPolicy(
      String dimension, String unit, BigDecimal quantity, Duration duration) {}
}
