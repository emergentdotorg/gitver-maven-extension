package lol.wud.gitver.git;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lol.wud.gitver.maven.CommandOptions;
import lol.wud.gitver.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Gathers the {@link GitSituation} of a project: the repository state, overridden by command
 * options or, for a detached head, by the environment of a supported CI system.
 */
@Slf4j
public class GitSituationFactory {

  private final CommandOptions options;
  private final Map<String, String> environment;

  public GitSituationFactory(CommandOptions options) {
    this(options, System.getenv());
  }

  GitSituationFactory(CommandOptions options, Map<String, String> environment) {
    this.options = options;
    this.environment = environment;
  }

  /**
   * @return the situation of the repository containing the pom, or null if there is none
   */
  public @Nullable GitSituation create(File pomFile) {
    Optional<GitSituation> found = GitSituation.find(pomFile.getParentFile());
    if (found.isEmpty()) {
      return null;
    }
    GitSituation situation = found.get();
    Optional<GitSituation> fromOptions = fromCommandOptions(situation);
    if (fromOptions.isPresent()) {
      return fromOptions.get();
    }
    // CI systems usually check out a detached head and name the branch or tag in the environment
    if (situation.isDetached()) {
      return fromCiEnvironment(situation).orElse(situation);
    }
    return situation;
  }

  private Optional<GitSituation> fromCommandOptions(GitSituation situation) {
    String branch = trim(options.get(CommandOptions.GIT_BRANCH));
    String tag = trim(options.get(CommandOptions.GIT_TAG));
    if (branch != null || tag != null) {
      log.debug("override git branch with {} and tags with {}", branch, tag);
      return Optional.of(
          situation.withBranch(branch).withTags(tag != null ? List.of(tag) : List.of()));
    }

    String ref = options.get(CommandOptions.GIT_REF);
    if (ref == null) {
      return Optional.empty();
    }
    if (!ref.startsWith("refs/")) {
      throw new IllegalArgumentException(
          "invalid provided ref " + ref + " - needs to start with refs/");
    }
    log.debug("override git ref with {}", ref);
    return Optional.of(
        ref.startsWith("refs/tags/")
            ? situation.withBranch(null).withTags(List.of(ref))
            : situation.withBranch(ref).withTags(List.of()));
  }

  private Optional<GitSituation> fromCiEnvironment(GitSituation situation) {
    if ("true".equalsIgnoreCase(environment.get("GITHUB_ACTIONS"))) {
      return fromGitHubActions(situation);
    }
    if ("true".equalsIgnoreCase(environment.get("GITLAB_CI"))) {
      return fromGitLabCi(situation);
    }
    if ("true".equalsIgnoreCase(environment.get("CIRCLECI"))) {
      return fromCircleCi(situation);
    }
    if (StringUtil.isNotBlank(environment.get("JENKINS_HOME"))) {
      return fromJenkins(situation);
    }
    return Optional.empty();
  }

  private Optional<GitSituation> fromGitHubActions(GitSituation situation) {
    if (!isHeadCommit(situation, "GITHUB_SHA")) {
      return Optional.empty();
    }
    log.info("gather git situation from GitHub Actions environment variable: GITHUB_REF");
    String ref = getenv("GITHUB_REF");
    if (ref == null) {
      return Optional.empty();
    }
    return ref.startsWith("refs/tags/")
        ? withBranchOrTag(situation, null, ref)
        : withBranchOrTag(situation, ref, null);
  }

  private Optional<GitSituation> fromGitLabCi(GitSituation situation) {
    if (!isHeadCommit(situation, "CI_COMMIT_SHA")) {
      return Optional.empty();
    }
    log.info(
        "gather git situation from GitLab CI environment variables: CI_COMMIT_BRANCH,"
            + " CI_MERGE_REQUEST_SOURCE_BRANCH_NAME and CI_COMMIT_TAG");
    String branch = getenv("CI_COMMIT_BRANCH");
    String tag = getenv("CI_COMMIT_TAG");
    String mergeRequestBranch = getenv("CI_MERGE_REQUEST_SOURCE_BRANCH_NAME");
    return withBranchOrTag(situation, branch != null ? branch : mergeRequestBranch, tag);
  }

  private Optional<GitSituation> fromCircleCi(GitSituation situation) {
    if (!isHeadCommit(situation, "CIRCLE_SHA1")) {
      return Optional.empty();
    }
    log.info(
        "gather git situation from Circle CI environment variables: CIRCLE_BRANCH and"
            + " CIRCLE_TAG");
    return withBranchOrTag(situation, getenv("CIRCLE_BRANCH"), getenv("CIRCLE_TAG"));
  }

  private Optional<GitSituation> fromJenkins(GitSituation situation) {
    if (!isHeadCommit(situation, "GIT_COMMIT")) {
      return Optional.empty();
    }
    log.info("gather git situation from jenkins environment variables: BRANCH_NAME and TAG_NAME");
    String branch = getenv("BRANCH_NAME");
    String tag = getenv("TAG_NAME");
    // Jenkins sets BRANCH_NAME to the tag name when building a tag
    return branch != null && branch.equals(tag)
        ? withBranchOrTag(situation, null, tag)
        : withBranchOrTag(situation, branch, tag);
  }

  /** The branch takes precedence; a tag is added to the tags of the head. */
  private static Optional<GitSituation> withBranchOrTag(
      GitSituation situation, @Nullable String branch, @Nullable String tag) {
    if (branch != null) {
      log.debug("override git branch with {}", branch);
      return Optional.of(situation.withBranch(branch));
    }
    if (tag != null) {
      log.debug("add git tag {}", tag);
      return Optional.of(situation.withAddedTag(tag));
    }
    return Optional.empty();
  }

  private boolean isHeadCommit(GitSituation situation, String commitVariable) {
    return situation.getRev().equals(environment.get(commitVariable));
  }

  private @Nullable String getenv(String name) {
    String value = environment.get(name);
    log.debug("  {}: {}", name, value);
    return value;
  }

  private static @Nullable String trim(@Nullable String value) {
    return value != null ? value.trim() : null;
  }
}
