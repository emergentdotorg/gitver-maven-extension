package org.emergent.gittle.maven;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Created by qoomon on 30/11/2016. */
class BuildPropertiesTest {

  @Test
  void value() {
    // GIVEN

    // WHEN
    String projectGroupId = BuildProperties.projectGroupId();
    String projectArtifactId = BuildProperties.projectArtifactId();
    String projectVersion = BuildProperties.projectVersion();

    // THEN
    // assertThat(projectGroupId).isEqualTo("me.qoomon");
    // assertThat(projectArtifactId).isEqualTo("gittle-maven-extension");
    assertThat(projectGroupId).isEqualTo("org.emergent.gittle.maven");
    assertThat(projectArtifactId).isEqualTo("gittle-maven-extension");
    assertThat(projectVersion).isNotEmpty();
  }
}
