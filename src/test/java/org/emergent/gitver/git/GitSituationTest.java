package org.emergent.gitver.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.eclipse.jgit.lib.Constants.MASTER;
import static org.emergent.gitver.git.GitUtil.NO_COMMIT;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitSituationTest {

  @TempDir Path tempDir;

  @Test
  void revParse_emptyRepo() throws GitAPIException {

    // given
    Git.init().setInitialBranch("master").setDirectory(tempDir.toFile()).call();

    GitSituation situation = GitSituation.find(tempDir.toFile()).orElseThrow();

    // then
    assertThat(situation)
        .satisfies(
            it ->
                assertSoftly(
                    softly -> {
                      softly.assertThat(it.isClean()).isTrue();
                      softly.assertThat(it.getRev()).isEqualTo(NO_COMMIT);
                      softly.assertThat(it.getBranch()).isEqualTo(MASTER);
                      softly.assertThat(it.getTags()).isEmpty();
                    }));
  }

  @Test
  void revParse_nonEmptyRepo() throws GitAPIException {

    // given
    Git git = Git.init().setInitialBranch("master").setDirectory(tempDir.toFile()).call();

    RevCommit givenCommit = git.commit().setMessage("initial commit").setAllowEmpty(true).call();

    GitSituation situation = GitSituation.find(tempDir.toFile()).orElseThrow();

    // then
    assertThat(situation)
        .satisfies(
            it ->
                assertSoftly(
                    softly -> {
                      softly.assertThat(it.isClean()).isTrue();
                      softly.assertThat(it.getRev()).isEqualTo(givenCommit.name());
                      softly.assertThat(it.getBranch()).isEqualTo(MASTER);
                      softly.assertThat(it.getTags()).isEmpty();
                    }));
  }

  @Test
  void headSituation_onBranchWithTag() throws GitAPIException {

    // Given
    Git git = Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();
    RevCommit givenCommit = git.commit().setMessage("init").setAllowEmpty(true).call();
    String givenTag = "v1";
    git.tag().setName(givenTag).setObjectId(givenCommit).call();

    GitSituation situation = GitSituation.find(tempDir.toFile()).orElseThrow();

    // Then
    assertThat(situation)
        .satisfies(
            it ->
                assertSoftly(
                    softly -> {
                      softly.assertThat(it.isClean()).isTrue();
                      softly.assertThat(it.getRev()).isEqualTo(givenCommit.getName());
                      softly.assertThat(it.getBranch()).isEqualTo(MASTER);
                      softly.assertThat(it.getTags()).containsExactly(givenTag);
                    }));
  }

  @Test
  void headSituation_detachedHead() throws GitAPIException {

    // Given
    Git git = Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();
    RevCommit givenCommit = git.commit().setMessage("init").setAllowEmpty(true).call();
    git.checkout().setName(givenCommit.getName()).call();

    GitSituation situation = GitSituation.find(tempDir.toFile()).orElseThrow();

    // Then
    assertThat(situation)
        .satisfies(
            it ->
                assertSoftly(
                    softly -> {
                      softly.assertThat(it.isClean()).isTrue();
                      softly.assertThat(it.getRev()).isEqualTo(givenCommit.getName());
                      softly.assertThat(it.getBranch()).isNull();
                      softly.assertThat(it.getTags()).isEmpty();
                    }));
  }

  @Test
  void headSituation_detachedHeadWithTag() throws GitAPIException {

    // Given
    Git git = Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();
    RevCommit givenCommit = git.commit().setMessage("init").setAllowEmpty(true).call();
    String givenTag = "v1";
    git.tag().setName(givenTag).setObjectId(givenCommit).call();
    git.checkout().setName(givenTag).call();

    GitSituation situation = GitSituation.find(tempDir.toFile()).orElseThrow();

    // Then
    assertThat(situation)
        .satisfies(
            it ->
                assertSoftly(
                    softly -> {
                      softly.assertThat(it.isClean()).isTrue();
                      softly.assertThat(it.getRev()).isEqualTo(givenCommit.getName());
                      softly.assertThat(it.getBranch()).isNull();
                      softly.assertThat(it.getTags()).containsExactly(givenTag);
                    }));
  }

  @Test
  void situation_annotatedTagOnMaster() throws Exception {

    // Given
    Git git = Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();
    RevCommit givenCommit = git.commit().setMessage("initial commit").setAllowEmpty(true).call();

    String givenTag = "v1";
    git.tag().setAnnotated(true).setName(givenTag).call();

    GitSituation situation = GitSituation.find(tempDir.toFile()).orElseThrow();

    // Then
    assertThat(situation)
        .satisfies(
            it ->
                assertSoftly(
                    softly -> {
                      softly.assertThat(it.isClean()).isTrue();
                      softly.assertThat(it.getRev()).isEqualTo(givenCommit.getName());
                      softly.assertThat(it.getBranch()).isEqualTo(MASTER);
                      softly.assertThat(it.getTags()).containsExactly(givenTag);
                    }));
  }

  @Test
  void situation_annotatedTagDetached() throws Exception {

    // Given
    Git git = Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();
    RevCommit givenCommit = git.commit().setMessage("initial commit").setAllowEmpty(true).call();

    String givenTag = "v1";
    git.tag().setAnnotated(true).setName(givenTag).setObjectId(givenCommit).call();
    git.checkout().setName(givenTag).call();

    GitSituation situation = GitSituation.find(tempDir.toFile()).orElseThrow();

    // Then
    assertThat(situation)
        .satisfies(
            it ->
                assertSoftly(
                    softly -> {
                      softly.assertThat(it.isClean()).isTrue();
                      softly.assertThat(it.getRev()).isEqualTo(givenCommit.getName());
                      softly.assertThat(it.getBranch()).isNull();
                      softly.assertThat(it.getTags()).containsExactly(givenTag);
                    }));
  }

  @Test
  void situation_lightweightTagOnMaster() throws Exception {

    // Given
    Git git = Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();
    RevCommit givenCommit = git.commit().setMessage("initial commit").setAllowEmpty(true).call();

    String givenTag = "v1";
    git.tag().setAnnotated(false).setName(givenTag).call();

    GitSituation situation = GitSituation.find(tempDir.toFile()).orElseThrow();

    // Then
    assertThat(situation)
        .satisfies(
            it ->
                assertSoftly(
                    softly -> {
                      softly.assertThat(it.isClean()).isTrue();
                      softly.assertThat(it.getRev()).isEqualTo(givenCommit.getName());
                      softly.assertThat(it.getBranch()).isEqualTo(MASTER);
                      softly.assertThat(it.getTags()).containsExactly(givenTag);
                    }));
  }

  @Test
  void situation_lightweightTagDetached() throws Exception {

    // Given
    Git git = Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();
    RevCommit givenCommit = git.commit().setMessage("initial commit").setAllowEmpty(true).call();

    String givenTag = "v1";
    git.tag().setAnnotated(false).setName(givenTag).call();
    git.checkout().setName(givenTag).call();

    GitSituation situation = GitSituation.find(tempDir.toFile()).orElseThrow();

    // Then
    assertThat(situation)
        .satisfies(
            it ->
                assertSoftly(
                    softly -> {
                      softly.assertThat(it.isClean()).isTrue();
                      softly.assertThat(it.getRev()).isEqualTo(givenCommit.getName());
                      softly.assertThat(it.getBranch()).isNull();
                      softly.assertThat(it.getTags()).containsExactly(givenTag);
                    }));
  }

  @Test
  void withBranch_normalizesRefs() throws GitAPIException {
    Git git = Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();
    git.commit().setMessage("init").setAllowEmpty(true).call();
    GitSituation situation = GitSituation.find(tempDir.toFile()).orElseThrow();

    assertThat(situation.withBranch("refs/heads/feature/x").getBranch()).isEqualTo("feature/x");
    assertThat(situation.withBranch("refs/pull/7/head").getBranch()).isEqualTo("pull/7/head");
    assertThat(situation.withBranch(null).isDetached()).isTrue();
    assertThatThrownBy(() -> situation.withBranch("refs/tags/v1"))
        .isInstanceOf(IllegalArgumentException.class);
    // the original is unchanged
    assertThat(situation.getBranch()).isEqualTo(MASTER);
  }

  @Test
  void withTags_replacesAndAddsTags() throws GitAPIException {
    Git git = Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();
    RevCommit givenCommit = git.commit().setMessage("init").setAllowEmpty(true).call();
    git.tag().setName("v1").setObjectId(givenCommit).call();
    GitSituation situation = GitSituation.find(tempDir.toFile()).orElseThrow();

    assertThat(situation.withAddedTag("refs/tags/v2").getTags()).containsExactly("v1", "v2");
    assertThat(situation.withTags(List.of("refs/tags/v3")).getTags()).containsExactly("v3");
    assertThatThrownBy(() -> situation.withTags(List.of("refs/heads/main")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(situation.getTags()).containsExactly("v1");
  }

  @Test
  void withDescribeOptions_describesAgainstMatchingTags() throws GitAPIException {
    Git git = Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();
    RevCommit tagged = git.commit().setMessage("one").setAllowEmpty(true).call();
    git.tag().setName("v1.0.0").setObjectId(tagged).call();
    RevCommit otherTagged = git.commit().setMessage("two").setAllowEmpty(true).call();
    git.tag().setName("other").setObjectId(otherTagged).call();
    git.commit().setMessage("three").setAllowEmpty(true).call();
    GitSituation situation = GitSituation.find(tempDir.toFile()).orElseThrow();

    assertThat(situation.getDescription().tag()).isEqualTo("other");
    GitDescription description =
        situation.withDescribeOptions(Pattern.compile("v.+"), true).getDescription();
    assertThat(description.tag()).isEqualTo("v1.0.0");
    assertThat(description.distance()).isEqualTo(2);
  }
}
