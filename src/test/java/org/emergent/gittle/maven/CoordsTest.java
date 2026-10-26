package org.emergent.gittle.maven;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.junit.jupiter.api.Test;

class CoordsTest {

  @Test
  void versionComparison() {
    Coords gav1 = Coords.builder().setGroupId("group").setArtifactId("artifact").build();
    Coords gav2 = gav1.toBuilder().setVersion("1.0").build();
    Coords gav3 = gav1.toBuilder().setVersion("1.0-SNAPSHOT").build();
    assertThat(gav1.compareTo(gav2)).isEqualTo(-1);
    assertThat(gav1.compareTo(gav3)).isEqualTo(-1);
    assertThat(gav2.compareTo(gav3)).isEqualTo(1);
    assertThat(gav1.compareTo(gav1.toBuilder().setVersion("").build())).isEqualTo(0);
  }

  @Test
  void ofModel() {
    Model model = new Model();
    model.setGroupId("group");
    model.setArtifactId("artifact");
    model.setVersion("version");
    Coords coords = Coords.of(model);
    assertThat(coords).isNotNull();
    assertThat(coords.getGroupId()).isEqualTo("group");
    assertThat(coords.getArtifactId()).isEqualTo("artifact");
    assertThat(coords.getVersion()).isEqualTo("version");
  }

  @Test
  void ofModelWithParentNoInheritance() {
    Model model = new Model();
    model.setGroupId("group");
    model.setArtifactId("artifact");
    model.setVersion("version");
    Parent parent = new Parent();
    parent.setGroupId("parentGroup");
    parent.setArtifactId("parentArtifact");
    parent.setVersion("parentVersion");
    model.setParent(parent);
    Coords coords = Coords.of(model);
    assertThat(coords).isNotNull();
    assertThat(coords.getGroupId()).isEqualTo("group");
    assertThat(coords.getArtifactId()).isEqualTo("artifact");
    assertThat(coords.getVersion()).isEqualTo("version");
  }

  @Test
  void ofModelWithParentWithInheritance() {
    Parent parent = new Parent();
    parent.setGroupId("parentGroup");
    parent.setArtifactId("parentArtifact");
    parent.setVersion("parentVersion");
    Model model = new Model();
    model.setParent(parent);
    model.setArtifactId("artifact");
    Coords coords = Coords.of(model);
    assertThat(coords).isNotNull();
    assertThat(coords.getGroupId()).isEqualTo("parentGroup");
    assertThat(coords.getArtifactId()).isEqualTo("artifact");
    assertThat(coords.getVersion()).isEqualTo("parentVersion");
  }

  @Test
  void toStringTest() {
    Coords coords1 = Coords.builder().setGroupId("group").setArtifactId("artifact").build();
    assertThat(coords1).asString().isEqualTo("group:artifact");
    Coords coords2 = coords1.toBuilder().setVersion("version").build();
    assertThat(coords2).asString().isEqualTo("group:artifact:version");
  }
}
