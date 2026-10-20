package org.ayuvahak.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;

class ProductionRetentionRegisterTest {

  @Test
  void acceptsACompleteOwnerControlledRegisterWithoutAssumingAnyUniversalDuration() {
    ProductionProfileProperties.Retention retention = completeRegister();

    assertThat(retention.isValidRegister()).isTrue();
    assertThat(retention.getRegister())
        .hasSize(ProductionProfileProperties.RetentionCategory.values().length);
  }

  @Test
  void rejectsARegisterThatOmitsAnOwnerCategory() {
    ProductionProfileProperties.Retention retention = completeRegister();
    retention
        .getRegister()
        .remove(ProductionProfileProperties.RetentionCategory.PHASE_12_ANALYTICS);

    assertThat(retention.isValidRegister()).isFalse();
  }

  @Test
  void rejectsMisattributedSchedulesAndNonPositiveDurations() {
    ProductionProfileProperties.Retention retention = completeRegister();
    ProductionProfileProperties.RetentionSchedule audit =
        retention.getRegister().get(ProductionProfileProperties.RetentionCategory.PLATFORM_AUDIT);
    audit.setOwner(ProductionProfileProperties.RetentionOwner.REFERRAL);
    assertThat(retention.isValidRegister()).isFalse();

    audit.setOwner(ProductionProfileProperties.RetentionOwner.PLATFORM);
    audit.setRetentionPeriod(Duration.ZERO);
    assertThat(retention.isValidRegister()).isFalse();
  }

  @Test
  void requiresHoldUnresolvedWorkAndRestoreSafeguardsForEverySchedule() {
    ProductionProfileProperties.Retention retention = completeRegister();
    ProductionProfileProperties.RetentionSchedule schedule =
        retention
            .getRegister()
            .get(ProductionProfileProperties.RetentionCategory.PHASE_11_FEDERATION);
    schedule.setBlockWhileHeld(false);
    assertThat(retention.isValidRegister()).isFalse();

    schedule.setBlockWhileHeld(true);
    schedule.setBlockWhileUnresolved(false);
    assertThat(retention.isValidRegister()).isFalse();

    schedule.setBlockWhileUnresolved(true);
    schedule.setReapplyCompletedActionsBeforeServing(false);
    assertThat(retention.isValidRegister()).isFalse();
  }

  @Test
  void rejectsAutomatedExpiryActionsWithoutOwnerImplementationSupport() {
    ProductionProfileProperties.Retention retention = completeRegister();
    retention
        .getRegister()
        .get(ProductionProfileProperties.RetentionCategory.DIRECTORY_PROJECTIONS)
        .setExpiryAction(ProductionProfileProperties.RetentionExpiryAction.PURGE);

    assertThat(retention.isValidRegister()).isFalse();
  }

  @Test
  void rejectsTreatingReferralAccessRestrictionAsPhysicalMinimization() {
    ProductionProfileProperties.Retention retention = completeRegister();
    retention
        .getRegister()
        .get(ProductionProfileProperties.RetentionCategory.REFERRAL_CASES)
        .setExpiryAction(
            ProductionProfileProperties.RetentionExpiryAction.MINIMIZE_OR_PSEUDONYMIZE);

    assertThat(retention.isValidRegister()).isFalse();
  }

  @Test
  void acceptsOnlyTheExistingAutomatedOwnerExpiryActions() {
    ProductionProfileProperties.Retention retention = completeRegister();
    retention
        .getRegister()
        .get(ProductionProfileProperties.RetentionCategory.PLATFORM_EVENT_OUTBOX)
        .setExpiryAction(ProductionProfileProperties.RetentionExpiryAction.PURGE);
    retention
        .getRegister()
        .get(ProductionProfileProperties.RetentionCategory.PLATFORM_NOTIFICATION_PROJECTION)
        .setExpiryAction(ProductionProfileProperties.RetentionExpiryAction.EXPIRE_PROJECTION);

    assertThat(retention.isValidRegister()).isTrue();
  }

  private static ProductionProfileProperties.Retention completeRegister() {
    ProductionProfileProperties.Retention retention = new ProductionProfileProperties.Retention();
    retention.setScheduleId("SYN-RETENTION-2026-01");
    retention.setReviewedAt(LocalDate.of(2026, 10, 20));
    retention.setAccountableAuthority("SYN-RECORDS-AUTHORITY");
    EnumMap<
            ProductionProfileProperties.RetentionCategory,
            ProductionProfileProperties.RetentionSchedule>
        schedules = new EnumMap<>(ProductionProfileProperties.RetentionCategory.class);
    for (ProductionProfileProperties.RetentionCategory category :
        ProductionProfileProperties.RetentionCategory.values()) {
      ProductionProfileProperties.RetentionSchedule schedule =
          new ProductionProfileProperties.RetentionSchedule();
      schedule.setOwner(category.owner());
      schedule.setPurpose("Synthetic deployment purpose for " + category);
      schedule.setDataClass("SYNTHETIC_OPERATIONAL");
      schedule.setAccessPolicy("OWNER_RESTRICTED");
      schedule.setLifecycleTrigger("OWNER_DEFINED_CLOSED_OR_DELIVERED");
      schedule.setRetentionPeriod(Duration.ofDays(90));
      schedule.setGovernanceBasis("SYN-APPROVED-POLICY-001");
      schedule.setHoldReviewAuthority("SYN-REVIEWER-001");
      schedule.setExpiryAction(ProductionProfileProperties.RetentionExpiryAction.OWNER_REVIEW);
      schedule.setEvidenceDependencies("UNRESOLVED_WORK_AND_AUDIT");
      schedule.setBackupPropagation("REAPPLY_BEFORE_READINESS");
      schedule.setVerificationMethod("SYNTHETIC_OWNER_ACCEPTANCE");
      schedule.setBlockWhileHeld(true);
      schedule.setBlockWhileUnresolved(true);
      schedule.setReapplyCompletedActionsBeforeServing(true);
      schedules.put(category, schedule);
    }
    retention.setRegister(schedules);
    return retention;
  }
}
