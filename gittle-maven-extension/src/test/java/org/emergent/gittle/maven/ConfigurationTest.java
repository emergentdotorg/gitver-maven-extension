package org.emergent.gittle.maven;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class ConfigurationTest {

  @Test
  public void testDefaults() throws Exception {
    assertThat(Configuration.DEFAULT_INSTANCE.get()).isNotNull();
  }
}
