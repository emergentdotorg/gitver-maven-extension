package org.emergent.gitver.maven;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.eclipse.jgit.lib.Constants.MASTER;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.emergent.gitver.git.GitRefType;
import org.emergent.gitver.git.GitSituation;
import org.emergent.gitver.maven.Configuration.RefConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionPlaceholdersTest {

  private static final Instant BUILD_START = Instant.parse("2005-02-03T04:05:06Z");
  private static final RefConfig BRANCH_REF =
      RefConfig.builder().setType(GitRefType.BRANCH).setPattern(".+").build();

  @TempDir Path tempDir;

  private Git git;

  @BeforeEach
  void setUp() throws GitAPIException {
    git = Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();
  }

  @Test
  void buildTimestamp_usesBuildStart() throws Exception {
    git.commit().setMessage("init").setAllowEmpty(true).call();
    Map<String, Supplier<String>> placeholders = global(BRANCH_REF);

    assertThat(get(placeholders, "build.timestamp.iso")).isEqualTo("2005-02-03T04:05:06Z");
    assertThat(get(placeholders, "build.timestamp")).isEqualTo("1107403506");
    assertThat(get(placeholders, "build.timestamp.year.2digit")).isEqualTo("05");
    assertThat(get(placeholders, "commit.timestamp.iso")).isNotEqualTo("2005-02-03T04:05:06Z");
  }

  @Test
  void commitTimestamp_unknownWithoutCommits() throws Exception {
    Map<String, Supplier<String>> placeholders = global(BRANCH_REF);

    assertThat(get(placeholders, "commit.timestamp.datetime")).isEqualTo("00000101.000000");
    assertThat(get(placeholders, "commit.timestamp.iso")).isEqualTo("0000-01-01T00:00:00Z");
  }

  @Test
  void describeTagVersion_withLabel() throws Exception {
    RevCommit commit = git.commit().setMessage("init").setAllowEmpty(true).call();
    git.tag().setName("v1.2.3-rc1").setObjectId(commit).call();
    Map<String, Supplier<String>> placeholders = global(BRANCH_REF);

    assertThat(get(placeholders, "describe.tag.version")).isEqualTo("1.2.3-rc1");
    assertThat(get(placeholders, "describe.tag.version.core")).isEqualTo("1.2.3");
    assertThat(get(placeholders, "describe.tag.version.label")).isEqualTo("rc1");
    assertThat(get(placeholders, "describe.tag.version.label.next")).isEqualTo("rc2");
    assertThat(get(placeholders, "describe.tag.version.label.plus.describe.distance"))
        .isEqualTo("rc1");
    assertThat(get(placeholders, "describe.tag.version.next")).isEqualTo("1.2.3-rc2");
    assertThat(get(placeholders, "describe.tag.version.maven")).isEqualTo("1.2.3");
  }

  @Test
  void describeTagVersion_withDistance() throws Exception {
    RevCommit commit = git.commit().setMessage("one").setAllowEmpty(true).call();
    git.tag().setName("v1.2.3").setObjectId(commit).call();
    git.commit().setMessage("two").setAllowEmpty(true).call();
    git.commit().setMessage("three").setAllowEmpty(true).call();
    Map<String, Supplier<String>> placeholders = global(BRANCH_REF);

    assertThat(get(placeholders, "describe.distance")).isEqualTo("2");
    assertThat(get(placeholders, "describe.tag.version.patch.next.plus.describe.distance"))
        .isEqualTo("6");
    assertThat(get(placeholders, "describe.tag.version.maven")).isEqualTo("1.2.4-SNAPSHOT");
  }

  @Test
  void describeTagVersion_defaultsWithoutTag() throws Exception {
    git.commit().setMessage("init").setAllowEmpty(true).call();
    Map<String, Supplier<String>> placeholders = global(BRANCH_REF);

    assertThat(get(placeholders, "describe.tag")).isEqualTo("root");
    assertThat(get(placeholders, "describe.tag.version")).isEqualTo("0.0.0");
    assertThat(get(placeholders, "describe.tag.version.core")).isEqualTo("0.0.0");
  }

  @Test
  void refPatternGroups() throws Exception {
    git.commit().setMessage("init").setAllowEmpty(true).call();
    RefConfig ref = BRANCH_REF.toBuilder().setPattern("(?<kind>[a-z]+)").build();
    Map<String, Supplier<String>> placeholders = global(ref, "feature/abc");

    assertThat(get(placeholders, "ref.kind")).isEqualTo("feature");
    assertThat(get(placeholders, "ref.slug")).isEqualTo("feature-abc");
  }

  @Test
  void refPatternGroup_mayNotShadowPlaceholder() throws Exception {
    git.commit().setMessage("init").setAllowEmpty(true).call();
    RefConfig ref = BRANCH_REF.toBuilder().setPattern("(?<slug>.+)").build();

    assertThatThrownBy(() -> global(ref))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("${ref.slug}");
  }

  @Test
  void local_versionParts() {
    Map<String, Supplier<String>> placeholders =
        VersionPlaceholders.addLocal(new HashMap<>(), null, "2.3-beta4");

    assertThat(get(placeholders, "version.core")).isEqualTo("2.3");
    assertThat(get(placeholders, "version.patch")).isEqualTo("0");
    assertThat(get(placeholders, "version.label.prefixed")).isEqualTo("-beta4");
    assertThat(get(placeholders, "version.label.next")).isEqualTo("beta5");
    assertThat(get(placeholders, "version.release")).isEqualTo("2.3");
  }

  private Map<String, Supplier<String>> global(RefConfig ref) throws Exception {
    return global(ref, MASTER);
  }

  private Map<String, Supplier<String>> global(RefConfig ref, String refName) throws Exception {
    GitSituation situation = new GitSituation(git.getRepository());
    GitVersionDetails details = new GitVersionDetails(situation.getRev(), refName, ref);
    GitverSession session = new GitverSession(BUILD_START, new DefaultMavenExecutionRequest());
    return VersionPlaceholders.global(situation, details, session);
  }

  private static String get(Map<String, Supplier<String>> placeholders, String key) {
    assertThat(placeholders).containsKey(key);
    return requireNonNull(placeholders.get(key)).get();
  }
}
