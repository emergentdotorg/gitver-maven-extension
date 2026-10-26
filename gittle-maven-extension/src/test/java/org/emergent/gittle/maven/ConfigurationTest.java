package org.emergent.gittle.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.emergent.gittle.commons.GitRefType;
import org.emergent.gittle.maven.Configuration.PatchDescription;
import org.emergent.gittle.maven.Configuration.RefPatchDescription;
import org.emergent.gittle.maven.Configuration.RefPatchDescriptionList;
import org.junit.jupiter.api.Test;

public class ConfigurationTest {

  @Test
  public void testDefaults() throws Exception {
    Configuration config = Configuration.DEFAULT_INSTANCE.get();
    assertThat(config).isNotNull();
    assertThat(config.getDisable()).isEqualTo(false);
    assertThat(config.getUseRevision()).isNull();
    assertThat(config.getDescribeTagFirstParent()).isEqualTo(true);
    assertThat(config.getDescribeTagPattern()).isNull();
    assertThat(config.getProjectVersionPattern()).isNull();
    assertThat(config.getUpdatePom()).isEqualTo(false);
    assertThat(config.getRelatedProjects()).isEmpty();
    assertThat(config.getRev()).isNotNull();

    RefPatchDescriptionList refs = config.getRefs();
    assertThat(refs.getConsiderTagsOnBranches()).isEqualTo(false);
    assertThat(refs.getList().size()).isEqualTo(2);
    PatchDescription patchDescription1 = new PatchDescription();
    patchDescription1.version = "${describe.tag.version.next}";
    patchDescription1.describeTagFirstParent = true;
    RefPatchDescription refPatchDescription1 =
        new RefPatchDescription(GitRefType.BRANCH, Pattern.compile(".+"), patchDescription1);
    assertThat(refs.getList().get(0)).isEqualTo(refPatchDescription1);

    PatchDescription patchDescription2 = new PatchDescription();
    patchDescription2.version = "${ref.version}";
    patchDescription2.describeTagFirstParent = true;
    RefPatchDescription refPatchDescription2 =
        new RefPatchDescription(
            GitRefType.TAG, Pattern.compile("v(?<version>.*)"), patchDescription2);
    assertThat(refs.getList().get(1)).isEqualTo(refPatchDescription2);
  }
}
