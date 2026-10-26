package org.emergent.gittle.maven.extension;

import static org.emergent.gittle.core.Util.GITTLE_POM_XML;

import com.pivovarit.function.exception.WrappedException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.emergent.gittle.core.GAV;
import org.emergent.gittle.core.Throwing;
import org.emergent.gittle.core.Util;

/** Handles creating the updated pom file, and assigning it to the project model. */
@Slf4j
@Singleton
@Named("gittle-extension")
public class GittleMavenExtension extends AbstractMavenLifecycleParticipant {

  private static final Charset UTF_8 = StandardCharsets.UTF_8;

  @Inject private GittleSessionHolder sessionHolder;

  private final AtomicBoolean initialized = new AtomicBoolean(false);

  @Override
  public void afterSessionStart(MavenSession mavenSession) throws MavenExecutionException {
    try {
      Throwing.run(() -> afterSessionStart0(mavenSession));
    } catch (WrappedException e) {
      Throwable cause = e.getCause();
      if (cause instanceof MavenExecutionException th) {
        throw th;
      }
      throw new MavenExecutionException(cause.getMessage(), cause);
    }
  }

  private void afterSessionStart0(MavenSession mavenSession) {
    if (ExtensionUtil.shouldSkip(mavenSession)) {
      log.info("  gittle execution has been skipped by request of the user");
      sessionHolder.clear();
      return;
    }
    GittleSession gittleSession = ExtensionUtil.createGittleSession(mavenSession);

    boolean isDirty = gittleSession.isDirty();

    if (gittleSession.getConfiguration().failIfDirty && isDirty) {
      throw new IllegalStateException("repository is dirty");
    }

    // Put metadatas into Maven session properties

    Map<String, String> propsMap = gittleSession.getProperties();
    propsMap.forEach((k, v) -> log.debug("setting property {} with \"{}}\"", k, v));
    mavenSession.getUserProperties().putAll(propsMap);

    // Put metadatas into exportedProps file (if requested)

    ExtensionUtil.exportPropertiesPath(mavenSession)
        .ifPresent(
            exportPropsPath -> {
              try (OutputStream os = new FileOutputStream(exportPropsPath)) {
                Properties props = new Properties();
                props.putAll(propsMap);
                props.store(
                    os, "Output from " + ExtensionUtil.EXTENSION_ARTIFACT_ID + " execution.");
                log.info("Properties exported to file \"{}\"", exportPropsPath);
              } catch (IOException ex) {
                throw new IllegalArgumentException(
                    "Failed writing properties to \"" + exportPropsPath + "\"", ex);
              }
            });

    sessionHolder.setGittleSession(gittleSession);
  }

  @Override
  public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
    updateProjects(session);
  }

  private void updateProjects(MavenSession mavenSession) throws MavenExecutionException {
    try {
      mavenSession.getAllProjects().forEach(p -> updateProject(mavenSession, p));
    } catch (WrappedException e) {
      Throwable cause = e.getCause();
      if (cause instanceof MavenExecutionException th) {
        throw th;
      }
      throw new MavenExecutionException(cause.getMessage(), cause);
    }
  }

  private void updateProject(MavenSession session, MavenProject project) {
    if (Util.isDisabled()) {
      if (initialized.compareAndSet(false, true)) {
        log.debug("{} is disabled", getClass().getSimpleName());
      }
      return;
    }
    // GAV ogav = ExtensionUtil.toGav(project).sansVersion();
    Optional<GittleSession> gsession = sessionHolder.getGittleSession();
    if (gsession.isEmpty()) {
      return;
    }
    GittleSession sess = gsession.get();
    String newver = sess.version();

    Model originalModel = project.getModel();
    Path originalPomFile = originalModel.getPomFile().toPath().toAbsolutePath();
    Model reloadedModel = ExtensionUtil.readModelFromPom(originalPomFile);
    GAV gav = ExtensionUtil.toGav(reloadedModel);

    log.info(
        "gittle-maven-plugin is about to change project(s) version(s)\n{}{} -> {}",
        " ".repeat(4),
        gav,
        newver);

    Path gittlePomFile = originalPomFile.resolveSibling(GITTLE_POM_XML);
    log.warn(
        "Project has artifact {} with file {}",
        project.getArtifact() != null,
        Optional.ofNullable(project.getArtifact()).map(Artifact::getFile).orElse(null));

    // sessionHolder.getGittleSession().map(GittleSession::getProperties).ifPresent(Throwing.wrap(v
    // -> {
    Optional.ofNullable(session.getUserProperties())
        .map(p -> p.getProperty("revision"))
        .ifPresent(
            Throwing.wrap(
                revision -> {
                  try {
                    // project.setVersion(revision);
                    if (true) return;
                    String xml = Files.readString(originalPomFile, UTF_8);
                    xml = xml.replace("${revision}", revision);
                    Model newMod = ExtensionUtil.readModelFromString(xml);
                    // ExtensionUtil.copyVersions(originalModel, newMod);
                    Properties props = newMod.getProperties();
                    if (props.containsKey("revision")) {
                      props.setProperty("revision", revision);
                    }
                    // props.remove("revision");
                    ExtensionUtil.writeModelToPom(newMod, gittlePomFile);
                    // Files.writeString(gittlePomFile, xml, UTF_8, CREATE, TRUNCATE_EXISTING);
                    project.setPomFile(gittlePomFile.toFile());
                    log.debug("Updated project with newly generated gittle pom {}", gittlePomFile);
                  } catch (Exception e) {
                    log.error("Failed creating new gittle pom at {}", gittlePomFile, e);
                  }
                }));
  }
}
