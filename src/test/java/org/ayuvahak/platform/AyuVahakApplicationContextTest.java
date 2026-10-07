package org.ayuvahak.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class AyuVahakApplicationContextTest {

  @Test
  void startsTheCanonicalApplicationContext(ApplicationContext applicationContext) {
    assertThat(applicationContext.getBean(AyuVahakApplication.class)).isNotNull();
  }
}
