package org.emergent.gitver.git;

import static java.time.Instant.EPOCH;
import static java.time.ZoneOffset.UTC;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static org.emergent.gitver.git.GitUtil.NO_COMMIT;

import java.io.File;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.eclipse.jgit.lib.ObjectId;
import org.emergent.gitver.util.Memoized;
import org.jspecify.annotations.Nullable;

/**
 * The git state versions are derived from: the head commit, its branch and tags, and its
 * description relative to the most recent matching tag.
 *
 * <p>Instances are immutable; the <code>with</code> methods return adjusted copies. Anything that
 * requires reading the repository is computed lazily, at most once.
 */
public final class GitSituation {

  private final Head head;
  private final @Nullable String branch;
  private final Supplier<List<String>> tags;
  private final Pattern tagPattern;
  private final boolean firstParent;
  private final Supplier<GitDescription> description;

  /**
   * @return the situation of the repository containing <code>directory</code>, or empty if there is
   *     none
   */
  public static Optional<GitSituation> find(File directory) {
    return GitUtil.find(directory).map(GitSituation::new);
  }

  /** The situation of the repository's checked out head. */
  GitSituation(GitUtil git) {
    this(new Head(git), git.branch(), null, Pattern.compile(".*"), true);
  }

  private GitSituation(
      Head head,
      @Nullable String branch,
      @Nullable Supplier<List<String>> tags,
      Pattern tagPattern,
      boolean firstParent) {
    this.head = head;
    this.branch = branch;
    this.tags = tags != null ? tags : head.tags;
    this.tagPattern = tagPattern;
    this.firstParent = firstParent;
    this.description =
        Memoized.by(
            () -> head.git.describe(head.commit, tagPattern, firstParent, head.tagsByCommit.get()));
  }

  public File getRootDirectory() {
    return head.rootDirectory;
  }

  public String getRev() {
    return head.commit != null ? head.commit.getName() : NO_COMMIT;
  }

  public ZonedDateTime getTimestamp() {
    return head.timestamp.get();
  }

  public @Nullable String getBranch() {
    return branch;
  }

  public boolean isDetached() {
    return branch == null;
  }

  public List<String> getTags() {
    return tags.get();
  }

  public boolean isClean() {
    return head.clean.get();
  }

  public Pattern getTagPattern() {
    return tagPattern;
  }

  public GitDescription getDescription() {
    return description.get();
  }

  /**
   * @param branch a branch name or ref, e.g. <code>refs/heads/main</code>; null for a detached head
   */
  public GitSituation withBranch(@Nullable String branch) {
    return new GitSituation(head, normalizeBranch(branch), tags, tagPattern, firstParent);
  }

  /**
   * @param tags tag names or refs, e.g. <code>refs/tags/v1.0.0</code>, replacing the head's tags
   */
  public GitSituation withTags(List<String> tags) {
    List<String> names = tags.stream().map(GitSituation::normalizeTag).toList();
    return new GitSituation(head, branch, () -> names, tagPattern, firstParent);
  }

  /**
   * @param tag a tag name or ref, added to the head's tags
   */
  public GitSituation withAddedTag(String tag) {
    String name = normalizeTag(tag);
    Supplier<List<String>> current = tags;
    Supplier<List<String>> added =
        Memoized.by(() -> Stream.concat(current.get().stream(), Stream.of(name)).toList());
    return new GitSituation(head, branch, added, tagPattern, firstParent);
  }

  /**
   * @param tagPattern the tags considered when describing the head
   * @param firstParent whether to follow only the first parent of merge commits
   */
  public GitSituation withDescribeOptions(Pattern tagPattern, boolean firstParent) {
    return new GitSituation(head, branch, tags, requireNonNull(tagPattern), firstParent);
  }

  private static @Nullable String normalizeBranch(@Nullable String branch) {
    if (branch == null) {
      return null;
    }
    if (branch.startsWith("refs/tags/")) {
      throw new IllegalArgumentException("invalid branch ref: " + branch);
    }
    return branch
        // support default branches (heads)
        .replaceFirst("^refs/heads/", "")
        // support other refs e.g. GitHub pull requests refs/pull/1000/head
        .replaceFirst("^refs/", "");
  }

  private static String normalizeTag(String tag) {
    requireNonNull(tag);
    if (tag.startsWith("refs/") && !tag.startsWith("refs/tags/")) {
      throw new IllegalArgumentException("invalid tag ref: " + tag);
    }
    return tag.replaceFirst("^refs/tags/", "");
  }

  /** What is read from the repository, shared by all situations derived from it. */
  private static final class Head {
    private final GitUtil git;
    private final File rootDirectory;
    private final @Nullable ObjectId commit;
    private final Supplier<ZonedDateTime> timestamp;
    private final Supplier<Boolean> clean;
    private final Supplier<Map<ObjectId, List<String>>> tagsByCommit;
    private final Supplier<List<String>> tags;

    Head(GitUtil git) {
      ObjectId commit = git.head();
      this.git = git;
      this.rootDirectory = git.workTree();
      this.commit = commit;
      this.timestamp =
          Memoized.by(
              () -> commit != null ? git.commitTime(commit) : ZonedDateTime.ofInstant(EPOCH, UTC));
      this.clean = Memoized.by(git::isClean);
      this.tagsByCommit = Memoized.by(git::tagsByCommit);
      this.tags =
          Memoized.by(
              () ->
                  commit != null
                      ? tagsByCommit.get().getOrDefault(commit, emptyList())
                      : emptyList());
    }
  }
}
