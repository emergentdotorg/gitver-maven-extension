package org.emergent.gittle.maven.extension;

import static org.apache.maven.shared.utils.logging.MessageUtils.buffer;
import static org.emergent.gittle.core.Util.join;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.building.Source;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.building.DefaultModelProcessor;
import org.apache.maven.model.building.ModelProcessor;
import org.eclipse.sisu.Priority;
import org.eclipse.sisu.Typed;
import org.emergent.gittle.core.GAV;
import org.emergent.gittle.core.Util;
import org.jspecify.annotations.Nullable;

/** Handles calculating version properties from the Git history. */
@Slf4j
@Singleton
@Priority(1)
@Named("core-default")
@Typed(ModelProcessor.class)
public class GittleModelProcessor extends DefaultModelProcessor {

  @Inject private GittleSessionHolder gittleSessionHolder;

  private final Set<Path> relatedPoms = new HashSet<>();
  private final AtomicBoolean initialized = new AtomicBoolean(false);

  private final AtomicReference<@Nullable GAV> topGav = new AtomicReference<>();

  private final boolean addProperties;
  private final boolean addPlugin;

  public GittleModelProcessor() {
    addProperties = false;
    addPlugin = true;
  }

  @Override
  public Model read(File input, Map<String, ?> options) throws IOException {
    return processModel(super.read(input, options), options);
  }

  @Override
  public Model read(Reader input, Map<String, ?> options) throws IOException {
    return processModel(super.read(input, options), options);
  }

  @Override
  public Model read(InputStream input, Map<String, ?> options) throws IOException {
    return processModel(super.read(input, options), options);
  }

  public Model processModel(Model projectModel, Map<String, ?> options) {
    if (Util.isDisabled()) {
      if (initialized.compareAndSet(false, true)) {
        log.debug("{} is disabled", getClass().getSimpleName());
      }
      return projectModel;
    }

    Source pomSource = (Source) options.get(ModelProcessor.SOURCE);
    Optional<Path> pomPath =
        Optional.ofNullable(pomSource).map(Source::getLocation).map(File::new).map(File::toPath);
    pomPath.map(Path::toFile).ifPresent(projectModel::setPomFile);

    if (!pomPath.map(Path::toString).orElse("").endsWith(".xml")) {
      // Source poms end with .xml but dependency poms end with .pom
      return projectModel;
    }

    // This model processor is invoked for every POM on the classpath, including the plugins.
    // The first execution is with the project's pom though. We use strategyRef to avoid processing
    // other poms.
    // if (initialized.compareAndSet(false, true)) {
    //   relatedPoms.addAll(findRelatedProjects(projectModel));
    // }

    if (topGav.compareAndSet(null, ExtensionUtil.toGav(projectModel))) {
      relatedPoms.addAll(findRelatedProjects(projectModel));
    }

    pomPath
        .filter(relatedPoms::contains)
        .ifPresent(
            pp -> {
              log.debug("Processing model for {}", pp);
              relatedPoms.addAll(findRelatedProjects(projectModel));
              gittleSessionHolder
                  .getGittleSession()
                  .ifPresent(
                      gittleSession -> {
                        processRelatedProjects(projectModel, gittleSession);
                      });
            });

    return projectModel;
  }

  // private VersionStrategy getVersionStrategy(Model projectModel) {
  //   if (Util.useExistingRevision()) {
  //     String revision = relatedPoms.stream()
  //         .sorted(Comparator.comparing(p -> p.toString().length()))
  //         .map(ExtensionUtil::readModelFromPom)
  //         .map(m -> m.getProperties().getProperty(REVISION))
  //         .filter(Objects::nonNull).findFirst()
  //         .orElseGet(() -> System.getProperty(REVISION));
  //     return StrategyFactory.getInstance(revision);
  //   }
  //   Config config = loadConfig(projectModel);
  //   Coordinates extensionGAV = Util.getExtensionCoordinates();
  //   log.info(buffer().a("--- ")
  //       .mojo(extensionGAV)
  //       .a(" ")
  //       .strong("[core-extension]")
  //       .a(" ---")
  //       .build());
  //   File basedir = projectModel.getProjectDirectory();
  //   return StrategyFactory.getInstance(config, basedir);
  // }
  //
  // private Config loadConfig(Model projectModel) {
  //   Path currentDir = projectModel.getProjectDirectory().toPath().toAbsolutePath();
  //   Path extConfigFile = Util.getExtensionPropsFile(currentDir);
  //   Map<String, String> fileProps = Util.toMap(Util.loadPropsFromFile(extConfigFile));
  //   log.info("Loaded configuration from file {}:{}", extConfigFile, join(fileProps));
  //   //Map<String, String> normalized = Util.removePrefix(GITTLE_PREFIX, fileProps);
  //   Config config = Config.from(fileProps);
  //   if (!fileProps.equals(config.asMap())) {
  //     log.warn("Round-trip configuration to properties:{}",
  //         join(Util.appendPrefix(GITTLE_PREFIX, config.asMap())));
  //   }
  //   return config;
  // }

  private static Set<Path> findRelatedProjects(Model model) {
    Set<Path> relatedPoms = new HashSet<>();
    Path basedir = model.getProjectDirectory().toPath();
    log.debug("Finding related projects for {} {}", model.getArtifactId(), basedir);
    // Add main project absolute path
    relatedPoms.add(model.getPomFile().toPath());
    // Find modules
    List<Path> modulePoms =
        model.getModules().stream()
            .map(module -> basedir.resolve(module).resolve("pom.xml"))
            .toList();
    log.debug(
        "Modules found:{}",
        modulePoms.stream().map(Path::toString).collect(Collectors.joining("\n", "\n", "")));
    relatedPoms.addAll(modulePoms);
    return relatedPoms;
  }

  private void processRelatedProjects(Model model, GittleSession gittleSession) {
    String versionString = gittleSession.version();

    GAV projectGav =
        GAV.builder()
            .setGroupId(getGroupId(model).orElse(""))
            .setArtifactId(model.getArtifactId())
            .setVersion(versionString)
            .build();

    log.debug(
        "Project {}:{}, Computed version: {}",
        projectGav.getGroupId(),
        projectGav.getArtifactId(),
        buffer().strong(projectGav.getVersion()));

    // ExtensionUtil.replaceRevision(model, versionString);

    // model.setVersion(versionString);

    if (addProperties) {
      Map<String, String> newProps = gittleSession.getProperties();
      log.debug("Adding properties to project {}", buffer().mojo(projectGav).a(join(newProps)));
      // model.getProperties().putAll(newProps);
    }
    if (addPlugin) {
      ExtensionUtil.addBuildPlugin(model, Objects.requireNonNull(topGav.get()));
    }
  }

  private static Optional<String> getGroupId(Model projectModel) {
    Optional<String> groupId = Optional.ofNullable(projectModel.getGroupId());
    if (groupId.isEmpty()) {
      groupId = Optional.ofNullable(projectModel.getParent()).map(Parent::getGroupId);
    }
    return groupId;
  }
}
