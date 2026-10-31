package org.emergent.gitver.git;

import static java.time.ZoneOffset.UTC;
import static java.util.Collections.emptyList;
import static org.eclipse.jgit.lib.Constants.HEAD;
import static org.eclipse.jgit.lib.Constants.R_TAGS;
import static org.eclipse.jgit.lib.Repository.shortenRefName;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.emergent.gitver.util.GitverException;
import org.jspecify.annotations.Nullable;

/**
 * Read access to one git repository.
 *
 * <p>Every operation opens the repository and closes it again before returning, so instances hold
 * no resources and need no closing. Linked worktrees (<code>git worktree add</code>) are resolved
 * by JGit itself. Failures are thrown as {@link GitverException}.
 */
final class GitUtil {

  public static final String NO_COMMIT = "0000000000000000000000000000000000000000";

  private final File gitDir;
  private final File workTree;

  private GitUtil(File gitDir, File workTree) {
    this.gitDir = gitDir;
    this.workTree = workTree;
  }

  /**
   * @return the repository containing <code>directory</code>, or empty if there is none
   */
  static Optional<GitUtil> find(File directory) {
    try {
      FileRepositoryBuilder builder =
          new FileRepositoryBuilder().readEnvironment().findGitDir(directory);
      if (builder.getGitDir() == null) {
        return Optional.empty();
      }
      try (Repository repository = builder.build()) {
        return Optional.of(new GitUtil(repository.getDirectory(), repository.getWorkTree()));
      }
    } catch (IOException e) {
      throw new GitverException("Failed to open the git repository of " + directory, e);
    }
  }

  File workTree() {
    return workTree;
  }

  /**
   * @return the checked out commit, or null in a repository without commits
   */
  @Nullable ObjectId head() {
    return withRepository("resolve HEAD", repository -> repository.resolve(HEAD));
  }

  /**
   * @return the checked out branch, or null for a detached head
   */
  @Nullable String branch() {
    String branch = withRepository("read the branch", Repository::getBranch);
    return ObjectId.isId(branch) ? null : branch;
  }

  boolean isClean() {
    return withRepository(
        "read the status", repository -> Git.wrap(repository).status().call().isClean());
  }

  ZonedDateTime commitTime(ObjectId commit) {
    int commitTime =
        withRepository(
            "read commit " + commit.getName(),
            repository -> repository.parseCommit(commit).getCommitTime());
    return ZonedDateTime.ofInstant(Instant.ofEpochSecond(commitTime), UTC);
  }

  /**
   * @return the tags of <code>commit</code>, most relevant first
   */
  List<String> tagsPointAt(ObjectId commit) {
    return tagsByCommit().getOrDefault(commit, emptyList());
  }

  /**
   * @return the names of all tags, keyed by the commit they point at, most relevant first
   * @see GitTagComparator
   */
  Map<ObjectId, List<String>> tagsByCommit() {
    return withRepository("read the tags", GitUtil::tagsByCommit);
  }

  /**
   * Describes <code>commit</code> relative to its most recent tag matching <code>tagPattern</code>.
   */
  GitDescription describe(@Nullable ObjectId commit, Pattern tagPattern, boolean firstParent) {
    return describe(commit, tagPattern, firstParent, tagsByCommit());
  }

  /**
   * @param tagsByCommit the result of {@link #tagsByCommit()}
   */
  GitDescription describe(
      @Nullable ObjectId commit,
      Pattern tagPattern,
      boolean firstParent,
      Map<ObjectId, List<String>> tagsByCommit) {
    if (commit == null) {
      return new GitDescription(NO_COMMIT, "root", 0);
    }
    return withRepository(
        "describe " + commit.getName(),
        repository -> describe(repository, commit, tagPattern, firstParent, tagsByCommit));
  }

  @Override
  public String toString() {
    return "GitUtil(" + gitDir + ")";
  }

  /** Runs <code>function</code> against the opened repository, wrapping failures. */
  private <R> R withRepository(String action, RepositoryFunction<R> function) {
    try (Repository repository =
        new FileRepositoryBuilder()
            .readEnvironment()
            .setGitDir(gitDir)
            .setWorkTree(workTree)
            .setMustExist(true)
            .build()) {
      return function.apply(repository);
    } catch (IOException | GitAPIException e) {
      throw new GitverException("Failed to " + action + " in " + gitDir, e);
    }
  }

  @FunctionalInterface
  private interface RepositoryFunction<R> {
    R apply(Repository repository) throws IOException, GitAPIException;
  }

  private static Map<ObjectId, List<String>> tagsByCommit(Repository repository)
      throws IOException {
    RefDatabase refDatabase = repository.getRefDatabase();
    Map<ObjectId, List<Ref>> refsByCommit = new HashMap<>();
    for (Ref ref : refDatabase.getRefsByPrefix(R_TAGS)) {
      // annotated tags point at a tag object, which points at the commit
      Ref peeled = refDatabase.peel(ref);
      ObjectId peeledObjectId = peeled.getPeeledObjectId();
      ObjectId commit = peeledObjectId != null ? peeledObjectId : peeled.getObjectId();
      refsByCommit.computeIfAbsent(commit, key -> new ArrayList<>()).add(ref);
    }

    Map<ObjectId, List<String>> tagsByCommit = new HashMap<>();
    try (RevWalk revWalk = new RevWalk(repository)) {
      GitTagComparator tagComparator = new GitTagComparator(revWalk);
      refsByCommit.forEach(
          (commit, refs) ->
              tagsByCommit.put(
                  commit,
                  refs.stream()
                      .sorted(tagComparator)
                      .map(ref -> shortenRefName(ref.getName()))
                      .toList()));
    }
    return tagsByCommit;
  }

  private static GitDescription describe(
      Repository repository,
      ObjectId commit,
      Pattern tagPattern,
      boolean firstParent,
      Map<ObjectId, List<String>> tagsByCommit)
      throws IOException {
    int distance = 0;
    try (RevWalk walk = new RevWalk(repository)) {
      walk.setRetainBody(false);
      walk.setFirstParent(firstParent);
      walk.markStart(walk.parseCommit(commit));
      for (RevCommit ancestor : walk) {
        Optional<String> matchingTag =
            tagsByCommit.getOrDefault(ancestor, emptyList()).stream()
                .filter(tag -> tagPattern.matcher(tag).matches())
                .findFirst();
        if (matchingTag.isPresent()) {
          return new GitDescription(commit.getName(), matchingTag.get(), distance);
        }
        distance++;
      }
    }
    if (new File(repository.getCommonDirectory(), "shallow").isFile()) {
      throw new GitverException("couldn't find matching tag in shallow git repository");
    }
    return new GitDescription(commit.getName(), "root", distance);
  }
}
