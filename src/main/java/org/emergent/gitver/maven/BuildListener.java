package org.emergent.gitver.maven;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

@Slf4j
@Singleton
@Named("minimal-listener")
public class BuildListener extends AbstractMavenLifecycleParticipant {

  @Override
  public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
    logPropertyValue(session, "revision");
  }

  private static void logPropertyValue(MavenSession session, String propertyKey) {
    try {
      Optional<List<MavenProject>> allProjects = Optional.ofNullable(session.getAllProjects());
      Optional<String> propertyValue =
          Stream.concat(
                  Stream.of(session.getUserProperties(), session.getSystemProperties()),
                  allProjects.stream().flatMap(List::stream).map(MavenProject::getProperties))
              .filter(Objects::nonNull)
              .map(props -> props.getProperty(propertyKey))
              .filter(Objects::nonNull)
              .findFirst();
      log.debug("{} property value: {}", propertyKey, propertyValue.orElse("not found"));
    } catch (Exception e) {
      log.warn("Error while looking up {} property value", propertyKey, e);
    }
  }
}
