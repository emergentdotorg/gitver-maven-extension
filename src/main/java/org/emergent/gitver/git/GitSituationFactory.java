package org.emergent.gitver.git;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Repository;
import org.emergent.gitver.maven.CommandOptions;
import org.emergent.gitver.util.Throwing;
import org.jspecify.annotations.Nullable;

/**
 * Gathers the {@link GitSituation} of a project: the repository state, overridden by command
 * options or by the environment of a supported CI system.
 */
@Slf4j
public class GitSituationFactory {

  private final CommandOptions options;

  public GitSituationFactory(CommandOptions options) {
    this.options = options;
  }

  /**
   * @return the situation of the repository containing the pom, or null if there is none
   */
  @Nullable
  public GitSituation create(File pomFile) throws IOException {
    File baseDirectory = pomFile.getParentFile();
    Optional<Repository> repository = GitUtil.getRepository(baseDirectory);
    return repository.map(repo -> Throwing.get(() -> new MyGitSituation(repo))).orElse(null);
  }

  private class MyGitSituation extends GitSituation {

    public MyGitSituation(Repository repository) throws IOException {
      super(repository);
      // --- commandline arguments and environment variables
      {
        {
          String overrideBranch = options.get(CommandOptions.GIT_BRANCH);
          String overrideTag = options.get(CommandOptions.GIT_TAG);
          if (overrideBranch != null || overrideTag != null) {
            setBranch(Optional.ofNullable(overrideBranch).map(String::trim).orElse(null));
            setTags(Optional.ofNullable(overrideTag).map(String::trim).stream().toList());
            return;
          }
        }

        {
          String providedRef = options.get(CommandOptions.GIT_REF);
          if (providedRef != null) {
            if (!providedRef.startsWith("refs/")) {
              throw new IllegalArgumentException(
                  "invalid provided ref " + providedRef + " -  needs to start with refs/");
            }
            if (providedRef.startsWith("refs/tags/")) {
              setBranch(null);
              setTags(singletonList(providedRef));
            } else {
              setBranch(providedRef);
              setTags(emptyList());
            }
            return;
          }
        }
      }

      // --- try getting branch and tag situation from environment ---
      // skip if we are on a branch
      if (repository.getBranch() == null) {
        return;
      }

      // GitHub Actions support
      if ("true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))) {
        if (!this.getRev().equals(System.getenv("GITHUB_SHA"))) {
          return;
        }

        log.info("gather git situation from GitHub Actions environment variable: GITHUB_REF");
        String githubRef = System.getenv("GITHUB_REF");
        log.debug("  GITHUB_REF: {}", githubRef);

        if (githubRef.startsWith("refs/tags/")) {
          addTag(githubRef);
        } else {
          setBranch(githubRef);
        }
        return;
      }

      // GitLab CI support
      if ("true".equalsIgnoreCase(System.getenv("GITLAB_CI"))) {
        if (!this.getRev().equals(System.getenv("CI_COMMIT_SHA"))) {
          return;
        }

        log.info(
            "gather git situation from GitLab CI environment variables: CI_COMMIT_BRANCH,"
                + " CI_MERGE_REQUEST_SOURCE_BRANCH_NAME and CI_COMMIT_TAG");
        String commitBranch = System.getenv("CI_COMMIT_BRANCH");
        String commitTag = System.getenv("CI_COMMIT_TAG");
        String mrSourceBranch = System.getenv("CI_MERGE_REQUEST_SOURCE_BRANCH_NAME");
        log.debug("  CI_COMMIT_BRANCH: {}", commitBranch);
        log.debug("  CI_COMMIT_TAG: {}", commitTag);
        log.debug("  CI_MERGE_REQUEST_SOURCE_BRANCH_NAME: {}", mrSourceBranch);

        if (commitBranch != null) {
          setBranch(commitBranch);
        } else if (mrSourceBranch != null) {
          setBranch(mrSourceBranch);
        } else if (commitTag != null) {
          addTag(commitTag);
        }
        return;
      }

      // Circle CI support
      if ("true".equalsIgnoreCase(System.getenv("CIRCLECI"))) {
        if (!this.getRev().equals(System.getenv("CIRCLE_SHA1"))) {
          return;
        }

        log.info(
            "gather git situation from Circle CI environment variables: CIRCLE_BRANCH and"
                + " CIRCLE_TAG");
        String commitBranch = System.getenv("CIRCLE_BRANCH");
        String commitTag = System.getenv("CIRCLE_TAG");
        log.debug("  CIRCLE_BRANCH: {}", commitBranch);
        log.debug("  CIRCLE_TAG: {}", commitTag);

        if (commitBranch != null) {
          setBranch(commitBranch);
        } else if (commitTag != null) {
          addTag(commitTag);
        }
        return;
      }

      // Jenkins support
      if (System.getenv("JENKINS_HOME") != null
          && !System.getenv("JENKINS_HOME").trim().isEmpty()) {
        if (!this.getRev().equals(System.getenv("GIT_COMMIT"))) {
          return;
        }
        log.info(
            "gather git situation from jenkins environment variables: BRANCH_NAME and TAG_NAME");
        String commitBranch = System.getenv("BRANCH_NAME");
        String commitTag = System.getenv("TAG_NAME");
        log.debug("  BRANCH_NAME: {}", commitBranch);
        log.debug("  TAG_NAME: {}", commitTag);

        if (commitBranch != null) {
          if (commitBranch.equals(commitTag)) {
            addTag(commitBranch);
          } else {
            setBranch(commitBranch);
          }
        } else if (commitTag != null) {
          addTag(commitTag);
        }
        return;
      }
    }

    @Override
    protected void setBranch(@Nullable String branch) {
      log.debug("override git branch with {}", branch);
      super.setBranch(branch);
    }

    @Override
    protected void setTags(List<String> tags) {
      log.debug("override git tags with single tag {}", tags);
      super.setTags(tags);
    }

    @Override
    protected void addTag(String tag) {
      log.debug("add git tag {}", tag);
      super.addTag(tag);
    }
  }
}
