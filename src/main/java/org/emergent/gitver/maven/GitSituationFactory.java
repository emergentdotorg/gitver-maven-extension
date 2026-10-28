package org.emergent.gitver.maven;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

import java.io.File;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

/**
 * Gathers the {@link GitSituation} of a project: the repository state, overridden by command
 * options or by the environment of a supported CI system.
 */
@Slf4j
@NullUnmarked
final class GitSituationFactory {

  private final CommandOptions options;

  GitSituationFactory(CommandOptions options) {
    this.options = options;
  }

  /**
   * @return the situation of the repository containing the pom, or null if there is none
   */
  @Nullable GitSituation create(File pomFile) throws IOException {
    final File baseDirectory = pomFile.getParentFile();
    final FileRepositoryBuilder repositoryBuilder =
        new FileRepositoryBuilder().findGitDir(baseDirectory);
    if (repositoryBuilder.getGitDir() == null) {
      return null;
    }

    final Repository repository = repositoryBuilder.build();
    return new GitSituation(repository) {
      {
        handleEnvironment(repository);
      }

      private void handleEnvironment(Repository repository) throws IOException {
        // --- commandline arguments and environment variables
        {
          {
            String overrideBranch = options.get(CommandOptions.GIT_BRANCH);
            String overrideTag = options.get(CommandOptions.GIT_TAG);
            if (overrideBranch != null || overrideTag != null) {
              overrideBranch =
                  overrideBranch == null || overrideBranch.trim().isEmpty()
                      ? null
                      : overrideBranch.trim();
              setBranch(overrideBranch);

              overrideTag =
                  overrideTag == null || overrideTag.trim().isEmpty() ? null : overrideTag.trim();
              setTags(overrideTag == null ? emptyList() : singletonList(overrideTag));
              return;
            }
          }

          {
            final String providedRef = options.get(CommandOptions.GIT_REF);
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

      protected void setBranch(String branch) {
        log.debug("override git branch with {}", branch);
        super.setBranch(branch);
      }

      protected void setTags(@NonNull List<String> tags) {
        log.debug("override git tags with single tag {}", tags);
        super.setTags(tags);
      }

      protected void addTag(@NonNull String tag) {
        log.debug("add git tag {}", tag);
        super.addTag(tag);
      }
    };
  }
}
