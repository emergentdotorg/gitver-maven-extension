package lol.wud.gitver.maven;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;

/** Makes the maven session available to the {@link GitverModelProcessor} for its duration. */
@Singleton
@Named("gitver-extension")
public class GitverMavenExtension extends AbstractMavenLifecycleParticipant {

  @Inject private GitverSessionHolder sessionHolder;

  @Override
  public void afterSessionStart(MavenSession mavenSession) {
    sessionHolder.start(GitverSession.from(mavenSession));
  }

  @Override
  public void afterSessionEnd(MavenSession session) {
    sessionHolder.end();
  }
}
