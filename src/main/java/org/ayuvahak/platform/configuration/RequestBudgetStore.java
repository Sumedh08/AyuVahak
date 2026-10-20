package org.ayuvahak.platform.configuration;

import java.util.List;

/** Deployment-wide request budgets; implementations must reserve all charges atomically. */
public interface RequestBudgetStore {

  boolean tryAcquire(List<Charge> charges);

  enum Dimension {
    PRINCIPAL,
    SCOPE,
    PARTNER,
    OPERATION
  }

  /** Raw identity is transient input and must never be persisted or logged by an implementation. */
  record Charge(Dimension dimension, String budgetClass, String identity, int requestsPerMinute) {
    public Charge {
      if (dimension == null
          || budgetClass == null
          || budgetClass.isBlank()
          || identity == null
          || identity.isBlank()
          || requestsPerMinute < 1) {
        throw new IllegalArgumentException("request budget charge is invalid");
      }
    }
  }
}
