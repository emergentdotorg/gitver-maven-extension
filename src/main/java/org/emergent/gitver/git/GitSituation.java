package org.emergent.gitver.git;

import static java.time.Instant.EPOCH;
import static java.time.ZoneOffset.UTC;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static org.emergent.gitver.git.GitUtil.NO_COMMIT;
import static org.emergent.gitver.git.GitUtil.worktreesFix_getWorkTree;
import static org.emergent.gitver.git.GitUtil.worktreesFix_resolveHead;

import java.io.File;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.emergent.gitver.util.Memoized;
import org.emergent.gitver.util.MemoizedNullable;
import org.jspecify.annotations.Nullable;

public class GitSituation {

  private final Repository repository;
  @Getter private final File rootDirectory;

  private final ObjectId head;
  private final Supplier<ZonedDateTime> timestamp = Memoized.byThrowing(this::timestamp);
  private Supplier<@Nullable String> branch = MemoizedNullable.byThrowing(this::branch);

  private Supplier<List<String>> tags;

  private final Supplier<Boolean> clean;

  {
    tags = Memoized.byThrowing(this::tags);
    clean = Memoized.byThrowing(this::clean);
  }

  @Getter private Pattern tagPattern = Pattern.compile(".*");

  @Getter @Setter private boolean firstParent = true;

  private Supplier<GitDescription> description = Memoized.byThrowing(this::describe);

  public GitSituation(Repository repository) throws IOException {
    this.repository = repository;
    this.rootDirectory = worktreesFix_getWorkTree(repository);
    this.head = worktreesFix_resolveHead(repository);
  }

  public String getRev() {
    return head != null ? head.getName() : NO_COMMIT;
  }

  public ZonedDateTime getTimestamp() {
    return timestamp.get();
  }

  public @Nullable String getBranch() {
    return branch.get();
  }

  protected void setBranch(@Nullable String branch) {
    if (branch != null) {
      if (branch.startsWith("refs/tags/")) {
        throw new IllegalArgumentException("invalid branch ref" + branch);
      }
      branch =
          branch
              // support default branches (heads)
              .replaceFirst("^refs/heads/", "")
              // support other refs e.g. GitHub pull requests refs/pull/1000/head
              .replaceFirst("^refs/", "");
    }
    String finalBranch = branch;
    this.branch = () -> finalBranch;
  }

  public boolean isDetached() {
    return branch.get() == null;
  }

  public List<String> getTags() {
    return tags.get();
  }

  protected void addTag(String tag) {
    requireNonNull(tag);

    if (tag.startsWith("refs/") && !tag.startsWith("refs/tags/")) {
      throw new IllegalArgumentException("invalid tag ref" + tag);
    }

    String finalTag = tag.replaceFirst("^refs/tags/", "");

    Supplier<List<String>> currentTags = this.tags;
    this.tags =
        Memoized.by(
            () -> {
              List<String> tags1 = new ArrayList<>(currentTags.get());
              tags1.add(finalTag);
              return tags1;
            });
  }

  protected void setTags(List<String> tags) {
    requireNonNull(tags);
    tags.forEach(
        tag -> {
          requireNonNull(tag);
          if (tag.startsWith("refs/") && !tag.startsWith("refs/tags/")) {
            throw new IllegalArgumentException("invalid tag ref" + tag);
          }
        });

    tags = tags.stream().map(tag -> tag.replaceFirst("^refs/tags/", "")).collect(toList());

    List<String> finalTags = tags;
    this.tags = () -> finalTags;
  }

  public boolean isClean() {
    return clean.get();
  }

  public void setTagPattern(Pattern tagPattern) {
    this.tagPattern = requireNonNull(tagPattern);
    this.description = Memoized.byThrowing(this::describe);
  }

  public GitDescription getDescription() {
    return description.get();
  }

  private ZonedDateTime timestamp() throws IOException {
    return head != null
        ? GitUtil.revTimestamp(repository, head)
        : ZonedDateTime.ofInstant(EPOCH, UTC);
  }

  private @Nullable String branch() throws IOException {
    return GitUtil.branch(repository);
  }

  private List<String> tags() throws IOException {
    return head != null ? GitUtil.tagsPointAt(head, repository) : emptyList();
  }

  private boolean clean() throws GitAPIException {
    return GitUtil.status(repository).isClean();
  }

  private GitDescription describe() throws IOException {
    return GitUtil.describe(head, tagPattern, repository, firstParent);
  }
}
