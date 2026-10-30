package org.emergent.gitver.git;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.eclipse.jgit.lib.Constants.MASTER;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequest;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.emergent.gitver.maven.CommandOptions;
import org.emergent.gitver.maven.GitverSession;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitSituationFactoryTest {

  @TempDir Path tempDir;

  private Git git;
  private RevCommit head;

  @BeforeEach
  void setUp() throws GitAPIException {
    git = Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();
    head = git.commit().setMessage("init").setAllowEmpty(true).call();
  }

  @Test
  void create_outsideRepository() throws IOException {
    File pomFile = new File(tempDir.getRoot().toFile(), "pom.xml");
    // the file system root is not inside a git repository
    assertThat(create(Map.of(), Map.of(), pomFile)).isNull();
  }

  @Test
  void create_fromRepository() throws IOException {
    GitSituation situation = create(Map.of(), Map.of());
    assertThat(situation.getBranch()).isEqualTo(MASTER);
    assertThat(situation.getRev()).isEqualTo(head.getName());
  }

  @Test
  void create_branchAndTagOptions() throws IOException {
    GitSituation situation = create(Map.of("git.branch", " feature ", "git.tag", "v1"), Map.of());
    assertThat(situation.getBranch()).isEqualTo("feature");
    assertThat(situation.getTags()).containsExactly("v1");
  }

  @Test
  void create_tagRefOption() throws IOException {
    GitSituation situation = create(Map.of("git.ref", "refs/tags/v2"), Map.of());
    assertThat(situation.isDetached()).isTrue();
    assertThat(situation.getTags()).containsExactly("v2");
  }

  @Test
  void create_branchRefOption() throws IOException {
    GitSituation situation = create(Map.of("git.ref", "refs/heads/release"), Map.of());
    assertThat(situation.getBranch()).isEqualTo("release");
    assertThat(situation.getTags()).isEmpty();
  }

  @Test
  void create_invalidRefOption() {
    assertThatThrownBy(() -> create(Map.of("git.ref", "main"), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void create_ciEnvironmentIgnoredOnBranch() throws IOException {
    GitSituation situation = create(Map.of(), gitHub("refs/heads/feature"));
    assertThat(situation.getBranch()).isEqualTo(MASTER);
  }

  @Test
  void create_gitHubBranchWhenDetached() throws IOException, GitAPIException {
    detach();
    GitSituation situation = create(Map.of(), gitHub("refs/pull/7/merge"));
    assertThat(situation.getBranch()).isEqualTo("pull/7/merge");
  }

  @Test
  void create_gitHubTagWhenDetached() throws IOException, GitAPIException {
    detach();
    GitSituation situation = create(Map.of(), gitHub("refs/tags/v3"));
    assertThat(situation.isDetached()).isTrue();
    assertThat(situation.getTags()).containsExactly("v3");
  }

  @Test
  void create_ciEnvironmentForOtherCommitIgnored() throws IOException, GitAPIException {
    detach();
    Map<String, String> env =
        Map.of("GITHUB_ACTIONS", "true", "GITHUB_SHA", "abc", "GITHUB_REF", "refs/heads/x");
    assertThat(create(Map.of(), env).isDetached()).isTrue();
  }

  @Test
  void create_gitLabMergeRequestBranch() throws IOException, GitAPIException {
    detach();
    Map<String, String> env =
        Map.of(
            "GITLAB_CI", "true",
            "CI_COMMIT_SHA", head.getName(),
            "CI_MERGE_REQUEST_SOURCE_BRANCH_NAME", "feature");
    assertThat(create(Map.of(), env).getBranch()).isEqualTo("feature");
  }

  @Test
  void create_jenkinsTagBuild() throws IOException, GitAPIException {
    detach();
    Map<String, String> env =
        Map.of(
            "JENKINS_HOME", "/var/jenkins",
            "GIT_COMMIT", head.getName(),
            "BRANCH_NAME", "v4",
            "TAG_NAME", "v4");
    GitSituation situation = create(Map.of(), env);
    assertThat(situation.isDetached()).isTrue();
    assertThat(situation.getTags()).containsExactly("v4");
  }

  private Map<String, String> gitHub(String ref) {
    return Map.of("GITHUB_ACTIONS", "true", "GITHUB_SHA", head.getName(), "GITHUB_REF", ref);
  }

  private void detach() throws GitAPIException {
    git.checkout().setName(head.getName()).call();
  }

  private GitSituation create(Map<String, String> userProperties, Map<String, String> env)
      throws IOException {
    return requireNonNull(create(userProperties, env, tempDir.resolve("pom.xml").toFile()));
  }

  private static @Nullable GitSituation create(
      Map<String, String> userProperties, Map<String, String> env, File pomFile)
      throws IOException {
    MavenExecutionRequest request = new DefaultMavenExecutionRequest();
    userProperties.forEach(request.getUserProperties()::setProperty);
    CommandOptions options = new CommandOptions(new GitverSession(Instant.now(), request));
    return new GitSituationFactory(options, env).create(pomFile);
  }
}
