package org.emergent.gitver.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.building.Source;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelProcessor;
import org.codehaus.plexus.util.StringUtils;
import org.emergent.gitver.git.GitSituationFactory;
import org.emergent.gitver.util.Memoized;

/**
 * Maven entry point. Decides which models to version and orchestrates the three steps, each of
 * which lives in its own collaborator:
 *
 * <ol>
 *   <li>gather git information: {@link GitverContextFactory}, {@link GitSituationFactory}
 *   <li>calculate versions: {@link VersionFormatter}
 *   <li>update the maven model: {@link ModelUpdater}, {@link PomXmlWriter}, {@link
 *       UpdateAttachedMojo}
 * </ol>
 */
@Slf4j
@Singleton
@Named("core-default")
public class GitverModelProcessor implements ModelProcessor {

  @Inject private GitverSessionHolder sessionHolder;

  private boolean initialized = false;

  /** Empty when versioning is disabled or not applicable; valid once {@link #initialized}. */
  private Optional<GitverContext> context = Optional.empty();

  private final Map<File, Model> sessionModelCache = new HashMap<>();

  @Inject private List<ModelProcessor> modelProcessors;

  private final Memoized<ModelProcessor> delegate = Memoized.by(this::findDelegate);

  private ModelProcessor findDelegate() {
    ModelProcessor found =
        modelProcessors.stream()
            // Avoid circular dependency
            .filter(processor -> processor != this)
            .findFirst()
            // There is normally always at least one implementation available:
            // org.apache.maven.model.building.DefaultModelProcessor
            .orElseThrow(() -> new NoSuchElementException("Unable to find default ModelProcessor"));
    log.debug("Delegated ModelProcessor : {}", found);
    return found;
  }

  @Override
  public File locatePom(File projectDirectory) {
    return delegate.get().locatePom(projectDirectory);
  }

  @Override
  public Model read(File input, Map<String, ?> options) throws IOException {
    return processModel(delegate.get().read(input, options), options);
  }

  @Override
  public Model read(Reader input, Map<String, ?> options) throws IOException {
    return processModel(delegate.get().read(input, options), options);
  }

  @Override
  public Model read(InputStream input, Map<String, ?> options) throws IOException {
    return processModel(delegate.get().read(input, options), options);
  }

  public Model processModel(Model projectModel, Map<String, ?> options) throws IOException {
    // clone model before return to prevent concurrency issues
    return processModel0(projectModel, options).clone();
  }

  private Model processModel0(Model projectModel, Map<String, ?> options) throws IOException {
    // set model pom file
    Source pomSource = (Source) options.get(ModelProcessor.SOURCE);
    if (pomSource != null) {
      projectModel.setPomFile(new File(pomSource.getLocation()));
    } else {
      log.debug("skip model - no project model pom file");
      return projectModel;
    }

    if (!initialized) {
      context = createContext(projectModel);
      initialized = true;
    }

    if (context.isEmpty()) {
      return projectModel;
    }
    GitverContext ctx = context.get();

    Coords projectCoords = Coords.of(projectModel);
    if (StringUtils.isBlank(projectCoords.getVersion())) {
      log.debug("skip model - can not determine project version - {}", projectModel.getPomFile());
      return projectModel;
    }

    if (!ctx.isRelated(projectCoords)) {
      if (log.isTraceEnabled()) {
        log.trace("skip model - unrelated project - {}", projectModel.getPomFile());
      }
      return projectModel;
    }

    File canonicalProjectPomFile = projectModel.getPomFile().getCanonicalFile();

    // return cached calculated project model if present
    Model cachedProjectModel = sessionModelCache.get(canonicalProjectPomFile);
    if (cachedProjectModel != null) {
      return cachedProjectModel;
    }

    // add current project model to session project models
    sessionModelCache.put(canonicalProjectPomFile, projectModel);

    if (log.isInfoEnabled()) {
      log.info(LogHeaders.project(projectCoords));
    }

    ctx.modelUpdater().update(projectModel);

    // In case another ModelProcessor is used (like for example polyglot extension),
    // we need to work on the POM possibly translated into XML
    File locatedPom = locatePom(projectModel.getProjectDirectory());
    File gitVersionedPomFile = PomXmlWriter.write(projectModel, locatedPom);
    if (ctx.updateSourcePom()) {
      log.debug("updating original POM file");
      Files.copy(
          gitVersionedPomFile.toPath(), locatedPom.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    // git versioned pom can't be set as model pom right away, because it will break plugins, that
    // trying to update original pom file e.g.
    //   mvn versions:set -DnewVersion=1.0.0
    // That's why we need to add a build plugin that sets project pom to the git versioned pom file
    if (ctx.updateAttachedPom()) {
      log.debug("add version build plugin");
      UpdateAttachedMojo.install(projectModel);
    }

    log.info("");
    return projectModel;
  }

  private Optional<GitverContext> createContext(Model projectModel) throws IOException {
    if (log.isInfoEnabled()) {
      log.info("");
      log.info(LogHeaders.extension(MavenUtil.gitverCoords()));
    }

    // In case another ModelProcessor is used (like for example polyglot extension),
    // we need to work on the POM possibly translated into XML
    File pomFile = locatePom(projectModel.getProjectDirectory());
    if (pomFile == null) {
      log.debug("skip - project model does not belong to a local project");
      return Optional.empty();
    }
    if (!pomFile.isFile()) {
      log.debug("skip - pom file does not exist {}", pomFile);
      return Optional.empty();
    }

    Optional<GitverSession> session = sessionHolder.getGitverSession();
    if (session.isEmpty()) {
      log.warn("skip - no maven session present");
      return Optional.empty();
    }

    return new GitverContextFactory(session.get()).create(projectModel, pomFile);
  }
}
