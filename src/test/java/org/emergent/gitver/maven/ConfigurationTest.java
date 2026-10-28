package org.emergent.gitver.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;
import org.emergent.gitver.maven.Configuration.RefPatchDescription;
import org.junit.jupiter.api.Test;

class ConfigurationTest {

  @Test
  public void testDefaults() throws Exception {
    Configuration config = Configuration.getDefault();
    assertThat(config).isNotNull();
    assertThat(config.getDisable()).isEqualTo(false);
    assertThat(config.getUpdateAttachedPom()).isTrue();
    assertThat(config.getFollowFirstParentOnly()).isTrue();
    assertThat(config.getTagPattern()).isEqualTo("v(?<ver>.*)");
    assertThat(config.getProjectVersionPattern()).isNull();
    assertThat(config.getUpdateSourcePom()).isFalse();
    assertThat(config.getRelatedProjects()).isEmpty();
    assertThat(config.getRev()).isNotNull();

    List<RefPatchDescription> refs = config.getRefs();
    assertThat(config.getConsiderTagsOnBranches()).isEqualTo(false);
    assertThat(refs.size()).isEqualTo(2);

    PatchDescription patchDescription1 = new PatchDescription();
    patchDescription1.version = "${describe.tag.version.next}";
    patchDescription1.tagPattern = "v(?<ver>.*)";
    patchDescription1.updateAttachedPom = true;
    patchDescription1.updateSourcePom = false;
    patchDescription1.followFirstParentOnly = true;
    RefPatchDescription refPatchDescription1 =
        new RefPatchDescription(GitRefType.BRANCH, Pattern.compile(".+"), patchDescription1);
    assertThat(refs.get(0)).isEqualTo(refPatchDescription1);

    PatchDescription patchDescription2 = new PatchDescription();
    patchDescription2.version = "${ref.version}";
    patchDescription2.tagPattern = "v(?<ver>.*)";
    patchDescription2.updateAttachedPom = true;
    patchDescription2.updateSourcePom = false;
    patchDescription2.followFirstParentOnly = true;
    RefPatchDescription refPatchDescription2 =
        new RefPatchDescription(
            GitRefType.TAG, Pattern.compile("v(?<version>.*)"), patchDescription2);
    assertThat(refs.get(1)).isEqualTo(refPatchDescription2);
  }
}
