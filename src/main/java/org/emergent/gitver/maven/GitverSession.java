package org.emergent.gitver.maven;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.Builder.Default;
import lombok.Value;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.jspecify.annotations.Nullable;

@Value
@lombok.Builder(toBuilder = true, setterPrefix = "set")
public class GitverSession {
  Instant start;
  MavenExecutionRequest request;
  @Default Map<Path, Coords> projectCoords = new TreeMap<>();
  @Default Map<Path, Properties> projectProperties = new TreeMap<>();

  public static GitverSession from(MavenSession session) {
    return GitverSession.builder()
        .setStart(session.getStartTime().toInstant())
        .setRequest(session.getRequest())
        .build();
  }

  public Properties getUserProperties() {
    return request.getUserProperties();
  }

  public Properties getSystemProperties() {
    return request.getSystemProperties();
  }

  public @Nullable File getMultiModuleProjectDirectory() {
    return request.getMultiModuleProjectDirectory();
  }

  public Map<Coords, Properties> getCoordsProperties() {
    return projectCoords.keySet().stream()
        .map(
            path -> {
              Coords coords = projectCoords.get(path);
              Properties properties = projectProperties.get(path);
              return coords == null || properties == null ? null : Map.entry(coords, properties);
            })
        .filter(Objects::nonNull)
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }
}
