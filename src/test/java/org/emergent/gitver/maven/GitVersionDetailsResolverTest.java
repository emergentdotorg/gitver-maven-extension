package org.emergent.gitver.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.jgit.lib.Constants.MASTER;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.emergent.gitver.git.GitRefType;
import org.emergent.gitver.git.GitSituation;
import org.emergent.gitver.maven.Configuration.RefConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitVersionDetailsResolverTest {

  private static final RefConfig TAG =
      RefConfig.builder().setType(GitRefType.TAG).setPattern("v(?<version>.*)").build();
  private static final RefConfig RELEASE_BRANCH =
      RefConfig.builder().setType(GitRefType.BRANCH).setPattern("release-.*").build();
  private static final RefConfig COMMIT = RefConfig.builder().setType(GitRefType.COMMIT).build();

  @TempDir Path tempDir;

  private GitSituation situation;

  @BeforeEach
  void setUp() throws Exception {
    Git git = Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();
    git.commit().setMessage("init").setAllowEmpty(true).call();
    situation = new GitSituation(git.getRepository());
  }

  @Test
  void matchingBranch() {
    Optional<GitVersionDetails> details =
        resolve(situation.withBranch("release-1"), false, TAG, RELEASE_BRANCH, COMMIT);
    assertThat(details).hasValueSatisfying(it -> assertRef(it, RELEASE_BRANCH, "release-1"));
  }

  @Test
  void noMatchingBranch() {
    assertThat(resolve(situation.withBranch("feature"), false, RELEASE_BRANCH, COMMIT)).isEmpty();
  }

  @Test
  void matchingTagWhenDetached() {
    GitSituation detached = situation.withBranch(null).withTags(List.of("other", "v1.0.0"));
    assertThat(resolve(detached, false, RELEASE_BRANCH, TAG, COMMIT))
        .hasValueSatisfying(it -> assertRef(it, TAG, "v1.0.0"));
  }

  @Test
  void tagsOnBranchIgnoredByDefault() {
    GitSituation onBranch = situation.withBranch("release-1").withTags(List.of("v1.0.0"));
    assertThat(resolve(onBranch, false, TAG, RELEASE_BRANCH))
        .hasValueSatisfying(it -> assertRef(it, RELEASE_BRANCH, "release-1"));
    assertThat(resolve(onBranch, true, TAG, RELEASE_BRANCH))
        .hasValueSatisfying(it -> assertRef(it, TAG, "v1.0.0"));
  }

  @Test
  void commitWhenDetachedWithoutMatchingTag() {
    GitSituation detached = situation.withBranch(null).withTags(List.of());
    assertThat(resolve(detached, false, TAG, RELEASE_BRANCH, COMMIT))
        .hasValueSatisfying(it -> assertRef(it, COMMIT, situation.getRev()));
  }

  private static void assertRef(GitVersionDetails details, RefConfig ref, String refName) {
    assertThat(details.patchOptions()).isEqualTo(ref);
    assertThat(details.refName()).isEqualTo(refName);
  }

  private static Optional<GitVersionDetails> resolve(
      GitSituation situation, boolean considerTagsOnBranches, RefConfig... refs) {
    Configuration config =
        Configuration.builder()
            .setConsiderTagsOnBranches(considerTagsOnBranches)
            .setRefs(List.of(refs))
            .build();
    return GitVersionDetailsResolver.resolve(situation, config);
  }
}
