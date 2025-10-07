package lol.wud.gitver.maven;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MavenUtilTest {

  @Test
  void gitverCoords() {
    Coords coords = MavenUtil.gitverCoords();
    assertThat(coords).isNotNull();
    assertThat(coords.getGroupId()).isEqualTo("lol.wud.gitver");
    assertThat(coords.getArtifactId()).isEqualTo("gitver-maven-extension");
    assertThat(coords.getVersion()).isNotBlank();
  }
}
