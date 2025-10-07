package lol.wud.gitver.maven;

import java.io.File;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.apache.maven.model.Model;

/**
 * What the extension knows about one maven session: the session, the versioning context resolved
 * when the first project is read, and the models versioned so far.
 *
 * <p>A new instance is started with every session, so nothing carries over between the builds of a
 * long-lived maven process such as mvnd or an IDE.
 */
final class GitverSessionState {

  private final GitverSession session;
  private final Map<File, Model> versionedModels = new ConcurrentHashMap<>();
  private boolean contextResolved;
  private Optional<GitverContext> context = Optional.empty();

  GitverSessionState(GitverSession session) {
    this.session = session;
  }

  GitverSession session() {
    return session;
  }

  /**
   * @param resolver resolves the context on the first call of the session
   * @return the context, or empty when versioning is disabled or not applicable
   */
  synchronized Optional<GitverContext> context(Supplier<Optional<GitverContext>> resolver) {
    if (!contextResolved) {
      context = resolver.get();
      contextResolved = true;
    }
    return context;
  }

  /** Versioned models by canonical pom file, so each project is versioned once per session. */
  Map<File, Model> versionedModels() {
    return versionedModels;
  }
}
