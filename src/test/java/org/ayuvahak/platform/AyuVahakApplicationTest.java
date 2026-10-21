package org.ayuvahak.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class AyuVahakApplicationTest {

  @Test
  void closesTheOneShotBootstrapContextAfterTheGrantCommandCompletes() {
    GenericApplicationContext context = new GenericApplicationContext();
    context.setEnvironment(
        new MockEnvironment().withProperty("ayuvahak.bootstrap.enabled", "true"));
    context.refresh();

    try {
      AyuVahakApplication.closeBootstrapContextIfRequested(context);

      assertThat(context.isActive()).isFalse();
    } finally {
      context.close();
    }
  }

  @Test
  void leavesTheNormalApplicationContextRunning() {
    GenericApplicationContext context = new GenericApplicationContext();
    context.setEnvironment(new MockEnvironment());
    context.refresh();

    try {
      AyuVahakApplication.closeBootstrapContextIfRequested(context);

      assertThat(context.isActive()).isTrue();
    } finally {
      context.close();
    }
  }
}
