package org.ayuvahak.platform.idempotency.configuration;

import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bounds PostgreSQL waits while concurrent callers arbitrate one command receipt. */
@Validated
@ConfigurationProperties("ayuvahak.idempotency")
public class CommandIdempotencyProperties {

  private Duration receiptLockWait = Duration.ofSeconds(5);

  public Duration getReceiptLockWait() {
    return receiptLockWait;
  }

  public void setReceiptLockWait(Duration receiptLockWait) {
    this.receiptLockWait = receiptLockWait;
  }

  @AssertTrue(
      message =
          "idempotency receipt lock wait must be at least one millisecond and no greater than 30 seconds")
  public boolean isValid() {
    return receiptLockWait != null
        && !receiptLockWait.isNegative()
        && !receiptLockWait.isZero()
        && receiptLockWait.toMillis() >= 1
        && receiptLockWait.compareTo(Duration.ofSeconds(30)) <= 0;
  }
}
