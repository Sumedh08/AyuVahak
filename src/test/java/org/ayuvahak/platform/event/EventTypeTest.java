package org.ayuvahak.platform.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EventTypeTest {

  @Test
  void parsesVersionedContextOwnedEventName() {
    EventType eventType = EventType.parse("identity-access.grant-created.v1");

    assertThat(eventType.context()).isEqualTo("identity-access");
    assertThat(eventType.fact()).isEqualTo("grant-created");
    assertThat(eventType.majorVersion()).isEqualTo(1);
    assertThat(eventType.toString()).isEqualTo("identity-access.grant-created.v1");
  }

  @Test
  void rejectsMalformedOrUnversionedEventNames() {
    assertThatThrownBy(() -> EventType.parse("GrantCreated"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EventType.parse("identity_access.grant-created.v1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EventType.parse("identity-access.grant.created.v1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EventType.parse("identity-access.grant-created.v0"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EventType.parse("identity-access.grant-created.v01"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
