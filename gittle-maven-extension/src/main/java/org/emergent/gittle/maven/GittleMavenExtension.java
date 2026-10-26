package org.emergent.gittle.maven;

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
@Named("gittle-extension")
public class GittleMavenExtension extends AbstractMavenLifecycleParticipant {

  @Inject private GittleSessionHolder sessionHolder;

  @Override
  public void afterSessionStart(MavenSession mavenSession) throws MavenExecutionException {
    sessionHolder.setGittleSession(GittleSession.from(mavenSession));
  }

  @Override
  public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
    GittleSession gsession = sessionHolder.getGittleSession().orElseThrow();
    Map<Path, GAV> projectGavs = gsession.getProjectGavs();
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
                          projectGavs.put(
                              path,
                              new GAV(
                                  project.getGroupId(),
                                  project.getArtifactId(),
                                  project.getVersion()));
                          projectProperties.put(path, project.getProperties());
                        }));
  }

  @Override
  public void afterSessionEnd(MavenSession session) throws MavenExecutionException {
    sessionHolder.clear();
  }
}
