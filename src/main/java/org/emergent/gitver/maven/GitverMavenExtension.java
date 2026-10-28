package org.emergent.gitver.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;

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
}
