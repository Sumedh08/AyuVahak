package org.ayuvahak.platform.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AggregatePositionTest {

  @Test
  void carriesBothAggregateReferenceAndPositiveSequence() {
    AggregatePosition position = new AggregatePosition("grant/grant-7", 3);

    assertThat(position.aggregateRef()).isEqualTo("grant/grant-7");
    assertThat(position.sequence()).isEqualTo(3);
  }

  @Test
  void rejectsMissingAmbiguousOrNonPositiveOrderingMetadata() {
    assertThatThrownBy(() -> new AggregatePosition(" ", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AggregatePosition("grant/grant-7\n", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AggregatePosition("grant/grant-7", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
