package org.emergent.gittle.maven;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Value;
import org.apache.maven.execution.MavenSession;
import org.jspecify.annotations.Nullable;

@Value
@Builder(toBuilder = true, builderClassName = "Builder", setterPrefix = "set")
public class GittleSession {
  Instant start;
  Properties userProperties;
  Properties systemProperties;
  @Nullable File multiModuleProjectDirectory;

  @Default Map<Path, GAV> projectGavs = new TreeMap<>();
  @Default Map<Path, Properties> projectProperties = new TreeMap<>();

  public static GittleSession from(MavenSession session) {
    return GittleSession.builder()
        .setStart(session.getStartTime().toInstant())
        .setUserProperties(session.getUserProperties())
        .setSystemProperties(session.getSystemProperties())
        .setMultiModuleProjectDirectory(session.getRequest().getMultiModuleProjectDirectory())
        .build();
  }
}
