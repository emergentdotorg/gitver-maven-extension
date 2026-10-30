package org.emergent.gitver.maven;

import java.time.Instant;
import java.util.Properties;
import lombok.Value;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;

/** The parts of the {@link MavenSession} the extension needs while models are being read. */
@Value
public class GitverSession {
  Instant start;
  MavenExecutionRequest request;

  public static GitverSession from(MavenSession session) {
    return new GitverSession(session.getStartTime().toInstant(), session.getRequest());
  }

  public Properties getUserProperties() {
    return request.getUserProperties();
  }

  public Properties getSystemProperties() {
    return request.getSystemProperties();
  }
}
