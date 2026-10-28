package org.emergent.gitver.maven;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import lombok.Builder.Default;
import lombok.Value;
import org.apache.maven.execution.MavenSession;
import org.jspecify.annotations.Nullable;

@Value
@lombok.Builder(toBuilder = true, setterPrefix = "set")
public class GitverSession {
  Instant start;
  Properties userProperties;
  Properties systemProperties;
  @Nullable File multiModuleProjectDirectory;

  @Default Map<Path, Coords> projectCoords = new TreeMap<>();
  @Default Map<Path, Properties> projectProperties = new TreeMap<>();

  public static GitverSession from(MavenSession session) {
    return GitverSession.builder()
        .setStart(session.getStartTime().toInstant())
        .setUserProperties(session.getUserProperties())
        .setSystemProperties(session.getSystemProperties())
        .setMultiModuleProjectDirectory(session.getRequest().getMultiModuleProjectDirectory())
        .build();
  }
}
