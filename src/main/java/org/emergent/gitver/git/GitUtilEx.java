package org.emergent.gitver.git;

import static java.time.ZoneOffset.UTC;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.groupingBy;
import static org.eclipse.jgit.lib.Constants.R_TAGS;
import static org.eclipse.jgit.lib.Repository.shortenRefName;

import com.pivovarit.function.ThrowingFunction;
import com.pivovarit.function.ThrowingSupplier;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.NoWorkTreeException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.emergent.gitver.util.Throwing;
import org.jspecify.annotations.Nullable;

@Slf4j
final class GitUtilEx {

  public final String NO_COMMIT = "0000000000000000000000000000000000000000";

  private final File baseDir;

  public GitUtilEx(File baseDir) {
    this.baseDir = baseDir;
  }

  private <R, E extends Exception> R withGit(ThrowingFunction<Git, R, E> function) {
    return Throwing.get(
        () -> {
          try (Git git = getGitSupplier(baseDir).get()) {
            return function.apply(git);
          }
        });
  }

  private <R, E extends Exception> @Nullable R withNullable(
      ThrowingFunction<Git, @Nullable R, E> function) {
    return ThrowingSupplier.unchecked(
            () -> {
              try (Git git = Git.open(baseDir)) {
                return function.apply(git);
              }
            })
        .get();
  }

  public <R, E extends Exception> R withRepo(ThrowingFunction<Repository, R, E> function) {
    return withGit(git -> function.apply(git.getRepository()));
  }

  public Status status() throws GitAPIException {
    return withGit(git -> git.status().call());
  }

  public @Nullable String branch() throws IOException {
    return withNullable(
        git -> {
          String branch = git.getRepository().getBranch();
          return ObjectId.isId(branch) ? null : branch;
        });
  }

  public List<String> tagsPointAt(ObjectId revObjectId) throws IOException {
    return reverseTagRefMap().getOrDefault(revObjectId, emptyList());
  }

  public GitDescription describe(
      @Nullable ObjectId revObjectId, Pattern tagPattern, boolean firstParent) throws IOException {
    return describe(revObjectId, tagPattern, firstParent, reverseTagRefMap());
  }

  /**
   * @param tagsByCommit the result of {@link #reverseTagRefMap()}
   */
  GitDescription describe(
      @Nullable ObjectId revObjectId,
      Pattern tagPattern,
      boolean firstParent,
      Map<ObjectId, List<String>> tagsByCommit)
      throws IOException {
    if (revObjectId == null) {
      return new GitDescription(NO_COMMIT, "root", 0);
    }

    return withRepo(
        repository -> {
          // Walk back commit ancestors looking for tagged one
          try (RevWalk walk = new RevWalk(repository)) {
            walk.setRetainBody(false);
            walk.setFirstParent(firstParent);
            walk.markStart(walk.parseCommit(revObjectId));
            Iterator<RevCommit> walkIterator = walk.iterator();
            int depth = 0;
            while (walkIterator.hasNext()) {
              RevCommit rev = walkIterator.next();
              Optional<String> matchingTag =
                  tagsByCommit.getOrDefault(rev, emptyList()).stream()
                      .filter(tag -> tagPattern.matcher(tag).matches())
                      .findFirst();

              if (matchingTag.isPresent()) {
                return new GitDescription(revObjectId.getName(), matchingTag.get(), depth);
              }
              depth++;
            }

            if (isShallowRepository(repository)) {
              throw new IllegalStateException(
                  "couldn't find matching tag in shallow git repository");
            }

            return new GitDescription(revObjectId.getName(), "root", depth);
          }
        });
  }

  public boolean isShallowRepository(Repository repository) {
    return new File(repository.getDirectory(), "shallow").isFile();
  }

  public List<Ref> tags() throws IOException {
    return withRepo(
        repository -> {
          return repository.getRefDatabase().getRefsByPrefix(R_TAGS);
        });
  }

  public Map<ObjectId, List<String>> reverseTagRefMap() throws IOException {
    return withRepo(
        repository -> {
          RefDatabase refDatabase = repository.getRefDatabase();
          try (RevWalk revWalk = new RevWalk(repository)) {
            GitTagComparator tagComparator = new GitTagComparator(revWalk);
            return tags().stream()
                .collect(groupingBy(Throwing.sneaky((Ref ref) -> peeledObjectId(refDatabase, ref))))
                .entrySet()
                .stream()
                .collect(
                    Collectors.toMap(
                        Entry::getKey,
                        e ->
                            e.getValue().stream()
                                .sorted(tagComparator)
                                .map(ref -> shortenRefName(ref.getName()))
                                .toList()));
          }
        });
  }

  /** The commit a tag points at, looking through annotated tags. */
  private static ObjectId peeledObjectId(RefDatabase refDatabase, Ref ref) throws IOException {
    Ref peeled = refDatabase.peel(ref);
    ObjectId peeledObjectId = peeled.getPeeledObjectId();
    return peeledObjectId != null ? peeledObjectId : peeled.getObjectId();
  }

  public ZonedDateTime revTimestamp(ObjectId rev) throws IOException {
    return withRepo(
        repository -> {
          Instant commitTime = Instant.ofEpochSecond(repository.parseCommit(rev).getCommitTime());
          return ZonedDateTime.ofInstant(commitTime, UTC);
        });
  }

  public @Nullable ObjectId worktreesFix_resolveHead() throws IOException {
    return withNullable(
        git -> {
          Repository repository = git.getRepository();
          File headFile = new File(repository.getDirectory(), Constants.HEAD);
          try {
            repository.getWorkTree();
            return repository.resolve(Constants.HEAD);
          } catch (NoWorkTreeException e) {
            if (!headFile.exists()) {
              throw e;
            }
          }

          String head = Files.readAllLines(headFile.toPath()).get(0);
          if (head.startsWith("ref:")) {
            String refPath = head.replaceFirst("^ref: *", "");
            ObjectId resolvedRef = repository.resolve(refPath);
            if (resolvedRef == null) {
              throw new IOException("Cannot resolve head ref '" + refPath + "'");
            }
            return resolvedRef;
          }
          return repository.resolve(head);
        });
  }

  public Git getGit() throws IOException {
    return getGitSupplier(baseDir).get();
  }

  private static Supplier<Git> getGitSupplier(File baseDirectory) throws IOException {
    List<ThrowingSupplier<@Nullable Git, Exception>> candidates =
        Stream.<ThrowingSupplier<@Nullable Git, Exception>>of(
                () -> gitOpenSupplier(baseDirectory),
                () -> repoBuildSupplier(baseDirectory),
                () -> workTreeSupplier(baseDirectory),
                () -> commonDirSupplier(baseDirectory))
            .toList();

    return () ->
        IntStream.range(0, candidates.size())
            .boxed()
            .map(
                i -> {
                  ThrowingSupplier<@Nullable Git, Exception> candidate = candidates.get(i);
                  try {
                    Git git = candidate.get();
                    Objects.requireNonNull(git);
                    Objects.requireNonNull(git.getRepository().getDirectory());
                    git.getRepository().getWorkTree();
                    log.info("Git provider [{}]: success", i);
                    return git;
                  } catch (Exception ex) {
                    log.warn("Git provider [{}]: failure", i, ex);
                    return null;
                  }
                })
            .filter(Objects::nonNull)
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Failed to initialize a repository from %s".formatted(baseDirectory)));
  }

  private static @Nullable Git gitOpenSupplier(File baseDirectory) throws IOException {
    File foundGitdir =
        new RepositoryBuilder().readEnvironment().findGitDir(baseDirectory).getGitDir();
    File resolvedDir = Optional.ofNullable(foundGitdir).orElse(baseDirectory);
    return Git.open(resolvedDir);
  }

  private static Git repoBuildSupplier(File baseDirectory) throws IOException {
    File foundGitdir =
        new RepositoryBuilder().readEnvironment().findGitDir(baseDirectory).getGitDir();
    File resolvedDir = Optional.ofNullable(foundGitdir).orElse(baseDirectory);
    return Git.wrap(new RepositoryBuilder().setGitDir(resolvedDir).readEnvironment().build());
  }

  private static Git workTreeSupplier(File baseDirectory) throws IOException {
    Path basepath = baseDirectory.toPath();
    Path gitdirFilePath =
        Optional.of(basepath.resolve("gitdir"))
            .filter(Files::isReadable)
            .filter(path -> !Files.isDirectory(path))
            .orElseThrow();
    String firstLineOfText = Files.readAllLines(gitdirFilePath).get(0);
    Path workTreePath = basepath.resolve(firstLineOfText);
    return Git.wrap(
        new RepositoryBuilder()
            .setGitDir(baseDirectory)
            .setWorkTree(workTreePath.toFile())
            .readEnvironment()
            .build());
  }

  private static Git commonDirSupplier(File baseDirectory) throws IOException {
    Path basepath = baseDirectory.toPath();
    Path commondirFilePath =
        Optional.of(basepath.resolve("commondir"))
            .filter(Files::isReadable)
            .filter(path -> !Files.isDirectory(path))
            .orElseThrow();
    String firstLineOfText = Files.readAllLines(commondirFilePath).get(0);
    Path commondirPath = basepath.resolve(firstLineOfText);
    return Git.wrap(
        new RepositoryBuilder().setGitCommonDir(commondirPath.toFile()).readEnvironment().build());
  }
}
