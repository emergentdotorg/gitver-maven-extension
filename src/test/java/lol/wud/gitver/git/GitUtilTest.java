package lol.wud.gitver.git;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.eclipse.jgit.lib.Constants.HEAD;
import static org.eclipse.jgit.lib.Constants.MASTER;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lol.wud.gitver.util.GitverException;
import lol.wud.gitver.util.Throwing;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitUtilTest {

  @TempDir Path tempDir;

  @Test
  void status_clean() throws GitAPIException {

    // given
    Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();

    // when
    boolean clean = gitUtil().isClean();

    // then
    assertThat(clean).isTrue();
  }

  @Test
  void status_dirty() throws GitAPIException, IOException {

    // given
    Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();

    boolean dummyFileCreated = new File(tempDir.toFile(), "README.md").createNewFile();
    assertThat(dummyFileCreated).isTrue();

    // when
    boolean clean = gitUtil().isClean();

    // then
    assertThat(clean).isFalse();
  }

  @Test
  void branch_emptyRepo() throws GitAPIException {

    // given
    Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();

    // when
    String branch = gitUtil().branch();

    // then
    assertThat(branch).isEqualTo(MASTER);
  }

  @Test
  void branch_nonEmptyRepo() throws GitAPIException {

    // given
    Git git = Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();
    RevCommit givenCommit = git.commit().setMessage("initial commit").setAllowEmpty(true).call();
    String givenBranchName = "feature";
    git.branchCreate().setName(givenBranchName).setStartPoint(givenCommit).call();
    git.checkout().setName(givenBranchName).call();

    // when
    String branch = gitUtil().branch();

    // then
    assertThat(branch).isEqualTo(givenBranchName);
  }

  @Test
  void tagsPointAt_emptyRepo() throws GitAPIException, IOException {

    // given
    Git git = Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();

    // when
    Map<ObjectId, List<String>> tags = gitUtil().tagsByCommit();

    // then
    assertThat(git.getRepository().resolve(HEAD)).isNull();
    assertThat(tags).isEmpty();
  }

  @Test
  void tagsPointAt_noTags() throws GitAPIException {

    // given
    Git git = Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();

    git.commit().setMessage("initial commit").setAllowEmpty(true).call();

    // when
    List<String> tags = gitUtil().tagsPointAt(head(git));

    // then
    assertThat(tags).isEmpty();
  }

  @Test
  void tagsPointAt_oneTag() throws GitAPIException {

    // given
    Git git = Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();

    RevCommit givenCommit = git.commit().setMessage("initial commit").setAllowEmpty(true).call();
    String givenTagName = "v1.0.0";
    git.tag().setName(givenTagName).setObjectId(givenCommit).call();

    // when
    List<String> tags = gitUtil().tagsPointAt(head(git));

    // then
    assertThat(tags).containsExactly(givenTagName);
  }

  @Test
  void tagsPointAt_multipleTags() throws GitAPIException {

    // given
    Git git = Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();

    RevCommit givenCommit = git.commit().setMessage("initial commit").setAllowEmpty(true).call();

    String givenTagName1 = "v21";
    git.tag().setName(givenTagName1).setObjectId(givenCommit).call();
    String givenTagName2 = "v1";
    git.tag().setName(givenTagName2).setObjectId(givenCommit).call();
    String givenTagName3 = "v2.1";
    git.tag().setName(givenTagName3).setObjectId(givenCommit).call();

    // when
    List<String> tags = gitUtil().tagsPointAt(head(git));

    // then
    assertThat(tags).containsExactly(givenTagName2, givenTagName3, givenTagName1);
  }

  @Test
  void tagsPointAt_lightweightTag() throws GitAPIException {

    // given
    Git git = Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();

    RevCommit givenCommit = git.commit().setMessage("initial commit").setAllowEmpty(true).call();

    String givenTagName = "v1.0.0-1234";
    git.tag().setName(givenTagName).setAnnotated(false).setObjectId(givenCommit).call();

    // when
    List<String> tags = gitUtil().tagsPointAt(head(git));

    // then
    assertThat(tags).containsExactlyInAnyOrder(givenTagName);
  }

  @Test
  void find_linkedWorktreeWithPackedRefs() throws Exception {

    // given
    Path mainDir = Files.createDirectory(tempDir.resolve("main"));
    Git git = Git.init().setInitialBranch(MASTER).setDirectory(mainDir.toFile()).call();
    RevCommit givenCommit = git.commit().setMessage("initial commit").setAllowEmpty(true).call();
    String givenBranch = "fixing/franco-2.1.5";
    git.branchCreate().setName(givenBranch).setStartPoint(givenCommit).call();
    git.tag().setName("v1.0.0").setObjectId(givenCommit).call();

    Path gitDir = mainDir.resolve(".git");
    Path branchRef = gitDir.resolve("refs").resolve("heads").resolve(givenBranch);
    Files.writeString(
        gitDir.resolve("packed-refs"),
        givenCommit.getName() + " refs/heads/" + givenBranch + System.lineSeparator());
    Files.delete(branchRef);

    // what `git worktree add ../linked fixing/franco-2.1.5` lays out
    Path worktreeDir = Files.createDirectory(tempDir.resolve("linked"));
    Path worktreeGitDir = Files.createDirectories(gitDir.resolve("worktrees").resolve("linked"));
    Files.writeString(worktreeDir.resolve(".git"), "gitdir: " + worktreeGitDir + "\n");
    Files.writeString(worktreeGitDir.resolve("gitdir"), worktreeDir.resolve(".git") + "\n");
    Files.writeString(worktreeGitDir.resolve("HEAD"), "ref: refs/heads/" + givenBranch + "\n");
    Files.writeString(worktreeGitDir.resolve("commondir"), "../..\n");

    // when
    GitUtil gitUtil = GitUtil.find(worktreeDir.toFile()).orElseThrow();

    // then
    assertThat(gitUtil.workTree().getCanonicalFile())
        .isEqualTo(worktreeDir.toFile().getCanonicalFile());
    assertThat(gitUtil.head()).isEqualTo(givenCommit.toObjectId());
    assertThat(gitUtil.branch()).isEqualTo(givenBranch);
    assertThat(gitUtil.tagsPointAt(givenCommit)).containsExactly("v1.0.0");
  }

  @Test
  void commitTime_unknownCommit() throws GitAPIException {
    Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();
    ObjectId unknown = ObjectId.fromString("1234567890123456789012345678901234567890");

    assertThatThrownBy(() -> gitUtil().commitTime(unknown))
        .isInstanceOf(GitverException.class)
        .hasMessageStartingWith("Failed to read commit " + unknown.getName())
        .hasCauseInstanceOf(IOException.class);
  }

  @Test
  void find_outsideRepository() {
    assertThat(GitUtil.find(tempDir.getRoot().toFile())).isEmpty();
  }

  private GitUtil gitUtil() {
    return GitUtil.find(tempDir.toFile()).orElseThrow();
  }

  private static ObjectId head(Git git) {
    return requireNonNull(Throwing.get(() -> git.getRepository().resolve(HEAD)));
  }

  @Test
  void describe() throws Exception {
    // given
    Git git = Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();

    RevCommit givenCommit = git.commit().setMessage("initial commit").setAllowEmpty(true).call();

    git.tag().setName("v2.1").setAnnotated(true).setObjectId(givenCommit).setMessage(".").call();
    git.tag().setName("v1.2").setAnnotated(true).setObjectId(givenCommit).setMessage(".").call();
    git.tag().setName("v2.3").setAnnotated(true).setObjectId(givenCommit).setMessage(".").call();
    Thread.sleep(1000);
    String givenTagName = "v1.3";
    git.tag()
        .setName(givenTagName)
        .setAnnotated(true)
        .setObjectId(givenCommit)
        .setMessage(".")
        .call();

    // when
    GitDescription description = gitUtil().describe(head(git), Pattern.compile("v.+"), true);

    // then
    assertThat(description)
        .satisfies(
            it -> {
              assertThat(it.commit()).isEqualTo(givenCommit.getName());
              assertThat(it.distance()).isEqualTo(0);
              assertThat(it.tag()).isEqualTo(givenTagName);
            });
  }
}
