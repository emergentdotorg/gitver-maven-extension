package org.emergent.gitver.maven;

import java.util.Optional;
import javax.inject.Named;
import javax.inject.Singleton;
import org.jspecify.annotations.Nullable;

/**
 * Holds the state of the running maven session. Components such as the {@link GitverModelProcessor}
 * are singletons that may outlive many sessions, so they keep no state of their own and look it up
 * here instead.
 */
@Singleton
@Named("gitver-session")
public class GitverSessionHolder {

  private volatile @Nullable GitverSessionState state;

  /** Starts a session, discarding anything left from a previous one. */
  void start(GitverSession session) {
    state = new GitverSessionState(session);
  }

  void end() {
    state = null;
  }

  /**
   * @return the state of the running session, or empty outside a session
   */
  Optional<GitverSessionState> current() {
    return Optional.ofNullable(state);
  }
}
