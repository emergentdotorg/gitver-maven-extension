package org.emergent.gitver.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

@Slf4j
@Singleton
@Named("gitver-extension")
public class GitverMavenExtension extends AbstractMavenLifecycleParticipant {

  @Inject private GitverSessionHolder sessionHolder;

  @Override
  public void afterSessionStart(MavenSession mavenSession) throws MavenExecutionException {
    sessionHolder.setGitverSession(GitverSession.from(mavenSession));
  }

  @Override
  public void afterSessionEnd(MavenSession session) throws MavenExecutionException {
    sessionHolder.clear();
  }

  @Override
  public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
    logPropertyValue(session, "revision");

    GitverSession gsession = sessionHolder.getGitverSession().orElseThrow();
    Map<Path, Coords> projectGavs = gsession.getProjectCoords();
    Map<Path, Properties> projectProperties = gsession.getProjectProperties();

    session
        .getAllProjects()
        .forEach(
            project ->
                Optional.ofNullable(project.getFile())
                    .map(File::getParentFile)
                    .map(File::toPath)
                    .map(Path::toAbsolutePath)
                    .ifPresent(
                        path -> {
                          projectGavs.put(path, Coords.of(project));
                          projectProperties.put(path, project.getProperties());
                        }));
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
      log.info("{} property value: {}", propertyKey, propertyValue.orElse("not found"));
    } catch (Exception e) {
      log.warn("Error while looking up {} property value", propertyKey, e);
    }
  }
}
