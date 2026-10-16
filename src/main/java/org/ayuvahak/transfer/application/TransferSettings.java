package org.ayuvahak.transfer.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

/** Deployment values consumed by Transfer policies, supplied by an outer configuration adapter. */
public interface TransferSettings {

  Duration getRequestDeadline();

  String getExpiryWorkerClientId();

  Duration getExpiryPollInterval();

  int getExpiryBatchSize();

  boolean isExpiryPollingEnabled();

  String getReservationWorkerClientId();

  String getOwnerEventConsumerClientId();

  String getTransportWorkerClientId();

  Duration getReservationPollInterval();

  Duration getReservationAttemptLease();

  int getReservationBatchSize();

  boolean isReservationPollingEnabled();

  Optional<ReservationPolicy> reservationPolicy();

  record ReservationPolicy(String dimension, String unit, BigDecimal quantity, Duration duration) {}
}
