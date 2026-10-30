package org.emergent.gitver.maven;

import java.util.Optional;
import javax.inject.Named;
import javax.inject.Singleton;
import org.jspecify.annotations.Nullable;

@Singleton
@Named("gitver-session")
public class GitverSessionHolder {

  @Nullable private GitverSession gitverSession;

  public Optional<GitverSession> getGitverSession() {
    return Optional.ofNullable(gitverSession);
  }

  public void setGitverSession(@Nullable GitverSession gitverSession) {
    this.gitverSession = gitverSession;
  }

  public void clear() {
    setGitverSession(null);
  }
}
