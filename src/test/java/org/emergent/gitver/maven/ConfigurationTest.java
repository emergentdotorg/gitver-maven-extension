package org.emergent.gitver.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.emergent.gitver.git.GitRefType;
import org.emergent.gitver.maven.Configuration.RefConfig;
import org.junit.jupiter.api.Test;

class ConfigurationTest {

  @Test
  public void testDefaults() throws Exception {
    Configuration config = Configuration.getDefault();
    assertThat(config).isNotNull();
    assertThat(config.isDisable()).isEqualTo(false);
    assertThat(config.isUpdateAttachedPom()).isTrue();
    assertThat(config.isUpdateSourcePom()).isFalse();
    assertThat(config.getProjectVersionPattern()).isEmpty();
    assertThat(config.getTagPattern()).isEqualTo("v(?<ver>.*)");
    assertThat(config.isFollowFirstParentOnly()).isTrue();
    assertThat(config.isConsiderTagsOnBranches()).isEqualTo(false);
    assertThat(config.getRelatedProjects()).isEmpty();

    List<RefConfig> refs = config.getRefs();
    assertThat(refs.size()).isEqualTo(3);

    RefConfig refDef =
        RefConfig.builder()
            .setTagPattern("v(?<ver>.*)")
            .setUpdateAttachedPom(config.isUpdateAttachedPom())
            .setUpdateSourcePom(config.isUpdateSourcePom())
            .setFollowFirstParentOnly(config.isFollowFirstParentOnly())
            .build();

    RefConfig refTag =
        refDef.toBuilder()
            .setType(GitRefType.TAG)
            .setPattern("v(?<version>.*)")
            .setVersion("${ref.version}")
            .build();

    assertThat(refs.get(0)).isEqualTo(refTag);

    RefConfig refBranch =
        refDef.toBuilder()
            .setType(GitRefType.BRANCH)
            .setPattern(".+")
            .setVersion("${describe.tag.version.maven}")
            .build();

    assertThat(refs.get(1)).isEqualTo(refBranch);

    RefConfig refCommit =
        refDef.toBuilder().setType(GitRefType.COMMIT).setVersion("${commit}-SNAPSHOT").build();

    assertThat(refs.get(2)).isEqualTo(refCommit);
  }
}
