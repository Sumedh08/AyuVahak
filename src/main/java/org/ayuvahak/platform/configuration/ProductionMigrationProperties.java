package org.ayuvahak.platform.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Operator-attested recovery-point evidence required before a production schema upgrade. */
@ConfigurationProperties("ayuvahak.production.migrations")
public class ProductionMigrationProperties {

  private String backupEvidenceReference = "";

  private String backupCompletedAt = "";

  public String getBackupEvidenceReference() {
    return backupEvidenceReference;
  }

  public void setBackupEvidenceReference(String backupEvidenceReference) {
    this.backupEvidenceReference = backupEvidenceReference;
  }

  public String getBackupCompletedAt() {
    return backupCompletedAt;
  }

  public void setBackupCompletedAt(String backupCompletedAt) {
    this.backupCompletedAt = backupCompletedAt;
  }
}
