package org.ayuvahak.platform.event;

/**
 * The aggregate reference and producer-issued monotonic sequence when causal ordering is needed.
 */
public record AggregatePosition(String aggregateRef, long sequence) {

  public AggregatePosition {
    if (aggregateRef == null
        || aggregateRef.isBlank()
        || !aggregateRef.equals(aggregateRef.strip())
        || aggregateRef.length() > 512
        || aggregateRef.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(
          "aggregate reference must be nonblank, trimmed, and contain no control characters");
    }
    if (sequence < 1) {
      throw new IllegalArgumentException("aggregate sequence must be positive");
    }
  }
}
