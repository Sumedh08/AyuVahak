package org.ayuvahak.directory.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.platform.core.IssuerId;

/** Directory-owned deduplication receipts for named inbound mCSD feed messages. */
public interface DirectoryMcsdFeedReceiptStore {

  void configureBoundedReceiptWait();

  boolean claim(
      IssuerId sourceNamespace,
      String externalMessageId,
      String operationCode,
      String requestFingerprint,
      String correlationId,
      Instant recordedAt);

  Optional<Receipt> findForUpdate(
      IssuerId sourceNamespace, String externalMessageId, String operationCode);

  void complete(
      IssuerId sourceNamespace,
      String externalMessageId,
      String operationCode,
      String requestFingerprint,
      List<DirectorySourceProjectionReconciliation.Outcome> outcomes,
      Instant completedAt);

  record Receipt(
      String requestFingerprint, List<DirectorySourceProjectionReconciliation.Outcome> outcomes) {
    public Receipt {
      outcomes = List.copyOf(outcomes);
    }
  }
}
