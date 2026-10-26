package org.emergent.gittle.maven;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MavenUtilTest {

  @Test
  void gittleCoords() {
    Coords coords = MavenUtil.gittleCoords();
    assertThat(coords).isNotNull();
    assertThat(coords.getGroupId()).isEqualTo("org.emergent.gittle.maven");
    assertThat(coords.getArtifactId()).isEqualTo("gittle-maven-extension");
    assertThat(coords.getVersion()).isNotEmpty();
  }
}
