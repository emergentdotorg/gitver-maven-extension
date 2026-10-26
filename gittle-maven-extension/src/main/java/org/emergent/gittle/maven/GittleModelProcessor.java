package org.emergent.gittle.maven;

import static java.lang.Boolean.parseBoolean;
import static java.time.format.DateTimeFormatter.ISO_INSTANT;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.apache.maven.shared.utils.StringUtils.rightPad;
import static org.apache.maven.shared.utils.logging.MessageUtils.buffer;
import static org.emergent.gittle.maven.MavenUtil.getBuild;
import static org.emergent.gittle.maven.MavenUtil.pomFile;
import static org.emergent.gittle.maven.MavenUtil.readModel;
import static org.emergent.gittle.maven.MavenUtil.readXml;
import static org.emergent.gittle.maven.MavenUtil.writeXml;

import com.google.inject.OutOfScopeException;
import de.pdark.decentxml.Document;
import de.pdark.decentxml.Element;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.building.Source;
import org.apache.maven.model.Build;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.ModelBase;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.model.Profile;
import org.apache.maven.model.ReportPlugin;
import org.apache.maven.model.Reporting;
import org.apache.maven.model.building.ModelProcessor;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.sisu.Priority;
import org.eclipse.sisu.Typed;
import org.emergent.gittle.commons.GitSituation;
import org.emergent.gittle.maven.Configuration.PatchDescription;
import org.emergent.gittle.maven.Configuration.RefPatchDescription;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

/** Wrapper for {@link ModelProcessor} to adapt versions. */
@Slf4j
@NullUnmarked
@Singleton
@Priority(1)
@Named("core-default")
@Typed(ModelProcessor.class)
public class GittleModelProcessor implements ModelProcessor {

  private static final String OPTION_NAME_GIT_REF = "git.ref";
  private static final String OPTION_NAME_GIT_TAG = "git.tag";
  private static final String OPTION_NAME_GIT_BRANCH = "git.branch";
  private static final String OPTION_NAME_DISABLE = "versioning.disable";
  private static final String OPTION_UPDATE_POM = "versioning.updatePom";
  private static final String OPTION_USE_REVISION = "versioning.useRevision";
  private static final String OPTION_CONFIG_FILE_NAME = "versioning.configFile";

  @Inject private GittleSessionHolder sessionHolder;

  private boolean initialized = false;

  private Configuration config;

  // --- following fields will be initialized by init() method
  // -------------------------------------------------------
  private File mvnDirectory;
  private GitSituation gitSituation;

  private boolean disabled = false;
  private GitVersionDetails gitVersionDetails;
  boolean updatePom = false;
  boolean useRevision = false;

  private PropsUtil propsUtil;
  private Set<Coords> relatedProjects;

  // ---- other fields
  // -----------------------------------------------------------------------------------------------

  private final Map<File, Model> sessionModelCache = new HashMap<>();

  private ModelProcessor delegate;

  @Inject
  void setDelegate(List<ModelProcessor> modelProcessors) {
    this.delegate =
        modelProcessors.stream()
            // Avoid circular dependency
            .filter(processor -> processor != this)
            .findFirst()
            // .orElseGet(DefaultModelProcessor::new);
            // There is normally always at least one implementation available:
            // org.apache.maven.model.building.DefaultModelProcessor
            .orElseThrow(() -> new NoSuchElementException("Unable to find default ModelProcessor"));
    log.debug("Delegated ModelProcessor : {}", this.delegate);
  }

  @Override
  public File locatePom(File projectDirectory) {
    return delegate.locatePom(projectDirectory);
  }

  @Override
  public Model read(File input, Map<String, ?> options) throws IOException {
    return processModel(delegate.read(input, options), options);
  }

  @Override
  public Model read(Reader input, Map<String, ?> options) throws IOException {
    return processModel(delegate.read(input, options), options);
  }

  @Override
  public Model read(InputStream input, Map<String, ?> options) throws IOException {
    return processModel(delegate.read(input, options), options);
  }

  private void init(Model projectModel) throws IOException {
    if (log.isInfoEnabled()) {
      log.info("");
      log.info(ProcessorUtil.extensionLogHeader(BuildProperties.projectGAV()));
    }

    // In case another ModelProcessor is used (like for example polyglot extension),
    // we need to work on the POM possibly translated into XML
    File pomFile = locatePom(projectModel.getProjectDirectory());
    if (pomFile == null) {
      log.debug("skip - project model does not belong to a local project");
      disabled = true;
      return;
    }

    if (!pomFile.isFile()) {
      log.debug("skip - pom file does not exist {}", pomFile);
      disabled = true;
      return;
    }

    GittleSession gittleSession;
    // check if session is available
    try {
      gittleSession =
          sessionHolder.getGittleSession().orElseThrow(() -> new OutOfScopeException(""));
      //      mavenSession = sessionScope.scope(Key.get(MavenSession.class), null).get();
    } catch (OutOfScopeException ex) {
      log.warn("skip - no maven session present");
      disabled = true;
      return;
    }

    log.debug("pom file: {}", pomFile);
    mvnDirectory = ProcessorUtil.findMvnDirectory(pomFile);
    log.debug(".mvn directory: {}", mvnDirectory);

    String configFileName = getCommandOption(OPTION_CONFIG_FILE_NAME);
    if (configFileName == null) {
      configFileName = Constants.CONFIG_FILE_NAME;
    }

    final File configFile = new File(mvnDirectory, configFileName);
    log.debug("read config from {}", configFile);
    config = ProcessorUtil.readConfig(configFile);

    // check if extension is disabled by command option
    final String commandOptionDisable = getCommandOption(OPTION_NAME_DISABLE);
    if (commandOptionDisable != null) {
      disabled = parseBoolean(commandOptionDisable);
      if (disabled) {
        log.info("skip - versioning is disabled by command option");
        return;
      }
    } else {
      // check if extension is disabled by config option
      disabled = config.disable != null && config.disable;
      if (disabled) {
        log.info("skip - versioning is disabled by config option");
        return;
      }
    }

    // determine git situation
    gitSituation = getGitSituation(pomFile);
    if (gitSituation == null) {
      log.warn("skip - project is not part of a git repository");
      disabled = true;
      return;
    }

    if (log.isDebugEnabled()) {
      log.debug("git situation:");
      log.debug("  root directory: {}", gitSituation.getRootDirectory());
      log.debug("  head commit: {}", gitSituation.getRev());
      log.debug("  head commit timestamp: {}", gitSituation.getTimestamp());
      log.debug("  head branch: {}", gitSituation.getBranch());
      log.debug("  head tags: {}", gitSituation.getTags());
      log.debug("  head description: {}", gitSituation.getDescription());
    }

    // determine git version details
    gitVersionDetails = ProcessorUtil.getGitVersionDetails(gitSituation, config);
    if (gitVersionDetails == null) {
      log.warn("skip - no matching <ref> configuration and no <rev> configuration defined");
      log.warn("git refs:");
      log.warn("  branch: {}", gitSituation.getBranch());
      log.warn("  tags: {}", gitSituation.getTags());
      log.warn("defined ref configurations:");
      config.refs.list.forEach(
          ref -> log.warn("  {} - pattern: {}", rightPad(ref.type.name(), 6), ref.pattern));
      disabled = true;
      return;
    }

    log.info(
        "matching ref: {} - {}", gitVersionDetails.refType().name(), gitVersionDetails.refName());
    final RefPatchDescription patchDescription = gitVersionDetails.patchDescription();
    log.info(
        "ref configuration: {} - pattern: {}",
        gitVersionDetails.refType().name(),
        patchDescription.pattern);
    if (patchDescription.describeTagPattern != null
        && !patchDescription.describeTagPattern.equals(".*")) {
      log.info("  describeTagPattern: {}", patchDescription.describeTagPattern);
      gitSituation.setDescribeTagPattern(patchDescription.describeTagPattern());
    }
    if (patchDescription.describeTagFirstParent != null) {
      log.info("  describeTagFirstParent: {}", patchDescription.describeTagFirstParent);
      gitSituation.setFirstParent(patchDescription.describeTagFirstParent);
    }
    if (patchDescription.version != null) {
      log.info("  version: {}", patchDescription.version);
    }
    if (!patchDescription.properties.isEmpty()) {
      log.info("  properties: ");
      patchDescription.properties.forEach((key, value) -> log.info("    {} - {}", key, value));
    }

    propsUtil = PropsUtil.create(gitSituation, gitVersionDetails, gittleSession, config);

    if (!patchDescription.userProperties.isEmpty()) {
      log.info("  userProperties: ");
      String projectVersion = Coords.of(projectModel).getVersion();
      patchDescription.userProperties.forEach(
          (key, value) -> {
            log.info("    {} - {}", key, value);
            gittleSession
                .getUserProperties()
                .put(key, getGitPropertyValue(value, "", projectVersion));
          });
    }

    updatePom = getUpdatePomOption(patchDescription);
    if (updatePom) {
      log.info("  updatePom: {}", updatePom);
    }

    useRevision = getUseRevisionOption(patchDescription);
    if (useRevision) {
      log.info("  useRevision: {}", useRevision);
    }

    // determine related projects
    relatedProjects = determineRelatedProjects(projectModel);
    if (log.isDebugEnabled()) {
      log.debug(buffer().strong("related projects:").build());
      relatedProjects.forEach(gav -> log.debug("  {}", gav));
    }

    log.info("");
  }

  // ---- model processing
  // -------------------------------------------------------------------------------------------

  public Model processModel(Model projectModel, Map<String, ?> options) throws IOException {
    // clone model before return to prevent concurrency issues
    return processModel0(projectModel, options).clone();
  }

  private Model processModel0(Model projectModel, Map<String, ?> options) throws IOException {
    // set model pom file
    final Source pomSource = (Source) options.get(ModelProcessor.SOURCE);
    if (pomSource != null) {
      projectModel.setPomFile(new File(pomSource.getLocation()));
    } else {
      log.debug("skip model - no project model pom file");
      return projectModel;
    }

    if (!initialized) {
      init(projectModel);
      initialized = true;
    }

    if (disabled) {
      return projectModel;
    }

    Coords projectCoords = Coords.of(projectModel);
    if (StringUtils.isBlank(projectCoords.getVersion())) {
      log.debug("skip model - can not determine project version - {}", projectModel.getPomFile());
      return projectModel;
    }

    if (!isRelatedProject(projectCoords)) {
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
      // log project header
      log.info(ProcessorUtil.projectLogHeader(projectCoords));
    }

    updateModel(projectModel, gitVersionDetails.patchDescription());

    File gitVersionedPomFile = writePomFile(projectModel);
    if (updatePom) {
      log.debug("updating original POM file");
      Files.copy(
          gitVersionedPomFile.toPath(),
          // In case another ModelProcessor is used (like for example polyglot extension),
          // we need to work on the POM possibly translated into XML
          locatePom(projectModel.getProjectDirectory()).toPath(),
          StandardCopyOption.REPLACE_EXISTING);
    }

    // git versioned pom can't be set as model pom right away,
    // because it will break plugins, that trying to update original pom file
    //   e.g. mvn versions:set -DnewVersion=1.0.0
    // That's why we need to add a build plugin that sets project pom file to git versioned pom file
    addBuildPlugin(projectModel);

    log.info("");
    return projectModel;
  }

  private void updateModel(Model projectModel, RefPatchDescription patchDescription) {
    Coords originalProjectCoords = Coords.of(projectModel);

    // final Map<String, String> propertyFormats = patchDescription.properties;
    // if (propertyFormats != null) {
    //   updatePropertyValues(projectModel, propertyFormats, originalProjectGAV);
    // }
    // addProjectProperties(projectModel);

    final String versionFormat = patchDescription.version;
    if (versionFormat != null) {
      String oldVersionInput = originalProjectCoords.getVersion();
      if ("${revision}".equals(oldVersionInput)) {
        Optional<@NonNull GittleSession> gsession = sessionHolder.getGittleSession();
        Properties projectProperties = projectModel.getProperties();
        Properties systemProperties =
            gsession.map(GittleSession::getSystemProperties).orElseGet(Properties::new);
        Properties userProperties =
            gsession.map(GittleSession::getUserProperties).orElseGet(Properties::new);

        String originalRev = userProperties.getProperty("revision.original");
        if (originalRev == null) {
          originalRev = Optional.ofNullable(projectProperties.getProperty("revision")).orElse("");
          userProperties.setProperty("revision.original", originalRev);
        }
        oldVersionInput = originalRev;
      }

      log.info(
          "Updating version {} -> {}",
          originalProjectCoords.toBuilder().setVersion(oldVersionInput),
          getGitVersion(versionFormat, originalProjectCoords.getVersion()));

      // if (useRevision) {
      //   log.warn("Using revision: {}", versionFormat);
      //   updateRevision(projectModel, versionFormat);
      // }
      updateParentVersion(projectModel, versionFormat);
      updateVersion(projectModel, versionFormat);
      updateDependencyVersions(projectModel, versionFormat);
      updatePluginVersions(projectModel, versionFormat);
    }

    Map<String, String> propertyFormats = patchDescription.properties;
    updatePropertyValues(projectModel, propertyFormats, originalProjectCoords);
    addProjectProperties(projectModel);

    // if (!useRevision) {
    updateProfiles(projectModel, patchDescription, originalProjectCoords);
    // }
    // profile section
  }

  private void updateProfiles(
      Model model, RefPatchDescription patchDescription, Coords originalProjectCoords) {
    List<Profile> profiles = model.getProfiles();

    // profile section
    if (!profiles.isEmpty()) {
      for (Profile profile : profiles) {
        String version = patchDescription.version;
        if (version != null) {
          updateDependencyVersions(profile, version);
          updatePluginVersions(profile, version);
        }

        Map<String, String> propertyFormats = patchDescription.properties;
        updatePropertyValues(profile, propertyFormats, originalProjectCoords);
      }
    }
  }

  private void updateParentVersion(Model projectModel, String versionFormat) {
    Parent parent = projectModel.getParent();
    if (parent != null) {
      Coords parentCoords = Coords.of(parent);
      if (isRelatedProject(parentCoords)) {
        String gitVersion = getGitVersion(versionFormat, parentCoords.getVersion());
        log.debug("set parent version to {} ({})", gitVersion, parentCoords);
        parent.setVersion(gitVersion);
      }
    }
  }

  private void updateVersion(Model projectModel, String versionFormat) {
    if (projectModel.getVersion() != null) {
      Coords projectCoords = Coords.of(projectModel);
      String gitVersion = getGitVersion(versionFormat, projectCoords.getVersion());
      log.info("set version to {}", gitVersion);
      projectModel.setVersion(gitVersion);
      sessionHolder
          .getGittleSession()
          .map(GittleSession::getUserProperties)
          .ifPresent(userProps -> userProps.put("revision", gitVersion));
    }
  }

  private void updatePropertyValues(
      ModelBase model,
      @Nullable Map<String, String> propertyFormats,
      Coords originalProjectCoords) {
    Properties modelProps = model.getProperties();
    // modelProps.remove("revision");
    if (Objects.isNull(propertyFormats) || propertyFormats.isEmpty()) {
      return;
    }
    // properties section
    modelProps.forEach(
        (modelPropertyName, modelPropertyValue) -> {
          String propertyFormat = propertyFormats.get((String) modelPropertyName);
          if (propertyFormat != null) {
            String gitPropertyValue =
                getGitPropertyValue(
                    propertyFormat,
                    (String) modelPropertyValue,
                    originalProjectCoords.getVersion());
            if (!gitPropertyValue.equals(modelPropertyValue)) {
              log.info("set property {} to {}", modelPropertyName, gitPropertyValue);
              model.addProperty((String) modelPropertyName, gitPropertyValue);
            }
          }
        });
  }

  private void updatePluginVersions(ModelBase model, String versionFormat) {
    BuildBase build = getBuild(model);
    if (build == null) {
      return;
    }
    // plugins section
    {
      List<Plugin> relatedPlugins = filterRelatedPlugins(build.getPlugins());
      if (!relatedPlugins.isEmpty()) {
        if (log.isDebugEnabled()) {
          log.debug(ProcessorUtil.sectionLogHeader("plugins", model));
        }
        for (Plugin plugin : relatedPlugins) {
          updateVersion(plugin, versionFormat);
        }
      }

      updatePluginDependencyVersions(model, versionFormat, build.getPlugins());
    }

    // plugin management section
    PluginManagement pluginManagement = build.getPluginManagement();
    if (pluginManagement != null) {
      List<Plugin> relatedPlugins = filterRelatedPlugins(pluginManagement.getPlugins());
      if (!relatedPlugins.isEmpty()) {
        if (log.isDebugEnabled()) {
          log.debug(ProcessorUtil.sectionLogHeader("plugin management", model));
        }
        for (Plugin plugin : relatedPlugins) {
          updateVersion(plugin, versionFormat);
        }
      }

      updatePluginDependencyVersions(model, versionFormat, pluginManagement.getPlugins());
    }

    // reporting section
    Reporting reporting = model.getReporting();
    if (reporting != null) {
      List<ReportPlugin> relatedPlugins = filterRelatedReportPlugins(reporting.getPlugins());
      if (!relatedPlugins.isEmpty()) {
        if (log.isDebugEnabled()) {
          log.debug(ProcessorUtil.sectionLogHeader("reporting plugins", model));
        }
        for (ReportPlugin plugin : relatedPlugins) {
          updateVersion(plugin, versionFormat);
        }
      }
    }
  }

  private void updatePluginDependencyVersions(
      ModelBase model, String versionFormat, List<Plugin> plugins) {
    List<Dependency> pluginDepsList =
        filterRelatedDependencies(
            plugins.stream().map(Plugin::getDependencies).flatMap(List::stream).collect(toList()));

    if (!pluginDepsList.isEmpty()) {
      if (log.isDebugEnabled()) {
        log.debug(ProcessorUtil.sectionLogHeader("plugins dependencies", model));
      }
      for (Dependency dep : pluginDepsList) {
        updateVersion(dep, versionFormat);
      }
    }
  }

  private void updateVersion(Plugin plugin, String versionFormat) {
    if (plugin.getVersion() != null) {
      Coords pluginCoords = Coords.of(plugin);
      String gitVersion = getGitVersion(versionFormat, pluginCoords.getVersion());
      if (log.isDebugEnabled()) {
        log.debug("{}: set version to {}", pluginCoords.getProjectId(), gitVersion);
      }
      plugin.setVersion(gitVersion);
    }
  }

  private void updateVersion(ReportPlugin plugin, String versionFormat) {
    if (plugin.getVersion() != null) {
      Coords pluginCoords = Coords.of(plugin);
      String gitVersion = getGitVersion(versionFormat, pluginCoords.getVersion());
      if (log.isDebugEnabled()) {
        log.debug("{}: set version to {}", pluginCoords.getProjectId(), gitVersion);
      }
      plugin.setVersion(gitVersion);
    }
  }

  private List<Plugin> filterRelatedPlugins(List<Plugin> plugins) {
    return plugins.stream().filter(it -> isRelatedProject(Coords.of(it))).collect(toList());
  }

  private List<ReportPlugin> filterRelatedReportPlugins(List<ReportPlugin> plugins) {
    return plugins.stream().filter(it -> isRelatedProject(Coords.of(it))).collect(toList());
  }

  private void updateDependencyVersions(ModelBase model, String versionFormat) {
    // dependencies section
    {
      List<Dependency> relatedDependencies = filterRelatedDependencies(model.getDependencies());
      if (!relatedDependencies.isEmpty()) {
        if (log.isDebugEnabled()) {
          log.debug(ProcessorUtil.sectionLogHeader("dependencies", model));
        }
        for (Dependency dependency : relatedDependencies) {
          updateVersion(dependency, versionFormat);
        }
      }
    }
    // dependency management section
    DependencyManagement dependencyManagement = model.getDependencyManagement();
    if (dependencyManagement != null) {
      List<Dependency> relatedDependencies =
          filterRelatedDependencies(dependencyManagement.getDependencies());
      if (!relatedDependencies.isEmpty()) {
        if (log.isDebugEnabled()) {
          log.debug(ProcessorUtil.sectionLogHeader("dependency management", model));
        }
        for (Dependency dependency : relatedDependencies) {
          updateVersion(dependency, versionFormat);
        }
      }
    }
  }

  private void updateVersion(Dependency dependency, String versionFormat) {
    if (dependency.getVersion() != null) {
      Coords dependencyCoords = Coords.of(dependency);
      String gitVersion = getGitVersion(versionFormat, dependencyCoords.getVersion());
      if (log.isDebugEnabled()) {
        log.debug("{}: set version to {}", dependencyCoords.getProjectId(), gitVersion);
      }
      dependency.setVersion(gitVersion);
    }
  }

  public List<Dependency> filterRelatedDependencies(List<Dependency> dependencies) {
    return dependencies.stream().filter(it -> isRelatedProject(Coords.of(it))).collect(toList());
  }

  private void addProjectProperties(Model projectModel) {
    Properties projectProperties = projectModel.getProperties();

    if (!projectProperties.contains("git.worktree")) {
      projectModel.addProperty("git.worktree", gitSituation.getRootDirectory().getAbsolutePath());
    }

    if (!projectProperties.contains("git.commit")) {
      projectModel.addProperty("git.commit", gitVersionDetails.commit());
    }
    if (!projectProperties.contains("git.commit.short")) {
      projectModel.addProperty("git.commit.short", gitVersionDetails.commit().substring(0, 7));
    }

    final ZonedDateTime headCommitDateTime = gitSituation.getTimestamp();
    if (!projectProperties.contains("git.commit.timestamp")) {
      projectModel.addProperty(
          "git.commit.timestamp", String.valueOf(headCommitDateTime.toEpochSecond()));
    }
    if (!projectProperties.contains("git.commit.timestamp.datetime")) {
      projectModel.addProperty(
          "git.commit.timestamp.datetime",
          headCommitDateTime.toEpochSecond() > 0
              ? headCommitDateTime.format(ISO_INSTANT)
              : "0000-00-00T00:00:00Z");
    }

    final String refName = gitVersionDetails.refName();
    if (!projectProperties.contains("git.ref")) {
      projectModel.addProperty("git.ref", refName);
    }
    if (!projectProperties.contains("git.ref.slug")) {
      projectModel.addProperty("git.ref.slug", ProcessorUtil.slugify(refName));
    }
  }

  private void addBuildPlugin(Model projectModel) {

    log.debug("add version build plugin");

    Plugin gitVersioningPlugin = GitVersioningMojo.asPluginWithExecution();
    if (projectModel.getBuild() == null) {
      projectModel.setBuild(new Build());
    }

    // add at index 0 to be executed before any other project plugin,
    // to prevent malfunctions with other plugins
    projectModel.getBuild().getPlugins().add(0, gitVersioningPlugin);

    // exclude this plugin from maven enforce plugin
    {
      LinkedList<Plugin> plugins = new LinkedList<>(projectModel.getBuild().getPlugins());
      if (projectModel.getBuild().getPluginManagement() != null) {
        plugins.addAll(projectModel.getBuild().getPluginManagement().getPlugins());
      }
      plugins.stream()
          .filter(it -> it.getKey().equals("org.apache.maven.plugins:maven-enforcer-plugin"))
          .forEach(
              plugin ->
                  plugin
                      .getExecutions()
                      .forEach(
                          execution -> {
                            Xpp3Dom configuration = (Xpp3Dom) execution.getConfiguration();
                            if (configuration != null) {
                              Xpp3Dom rules = configuration.getChild("rules");
                              if (rules != null) {
                                Xpp3Dom requirePluginVersions =
                                    rules.getChild("requirePluginVersions");
                                if (requirePluginVersions != null) {
                                  Xpp3Dom unCheckedPluginList =
                                      requirePluginVersions.getChild("unCheckedPluginList");
                                  if (unCheckedPluginList == null) {
                                    unCheckedPluginList = new Xpp3Dom("unCheckedPluginList");
                                    requirePluginVersions.addChild(unCheckedPluginList);
                                  }
                                  // prepend git versioning plugin to unCheckedPluginList
                                  unCheckedPluginList.setValue(
                                      gitVersioningPlugin.getKey()
                                          + ","
                                          + Objects.requireNonNullElse(
                                              unCheckedPluginList.getValue(), ""));
                                }
                              }
                            }
                          }));
    }
  }

  // ---- versioning
  // -------------------------------------------------------------------------------------------------

  private GitSituation getGitSituation(File pomFile) throws IOException {
    final File baseDirectory = pomFile.getParentFile();
    final FileRepositoryBuilder repositoryBuilder =
        new FileRepositoryBuilder().findGitDir(baseDirectory);
    if (repositoryBuilder.getGitDir() == null) {
      return null;
    }

    final Repository repository = repositoryBuilder.build();
    return new GitSituation(repository) {
      {
        handleEnvironment(repository);
      }

      private void handleEnvironment(Repository repository) throws IOException {
        // --- commandline arguments and environment variables
        {
          {
            String overrideBranch = getCommandOption(OPTION_NAME_GIT_BRANCH);
            String overrideTag = getCommandOption(OPTION_NAME_GIT_TAG);
            if (overrideBranch != null || overrideTag != null) {
              overrideBranch =
                  overrideBranch == null || overrideBranch.trim().isEmpty()
                      ? null
                      : overrideBranch.trim();
              setBranch(overrideBranch);

              overrideTag =
                  overrideTag == null || overrideTag.trim().isEmpty() ? null : overrideTag.trim();
              setTags(overrideTag == null ? emptyList() : singletonList(overrideTag));
              return;
            }
          }

          {
            final String providedRef = getCommandOption(OPTION_NAME_GIT_REF);
            if (providedRef != null) {
              if (!providedRef.startsWith("refs/")) {
                throw new IllegalArgumentException(
                    "invalid provided ref " + providedRef + " -  needs to start with refs/");
              }

              if (providedRef.startsWith("refs/tags/")) {
                setBranch(null);
                setTags(singletonList(providedRef));
              } else {
                setBranch(providedRef);
                setTags(emptyList());
              }
              return;
            }
          }
        }

        // --- try getting branch and tag situation from environment ---
        // skip if we are on a branch
        if (repository.getBranch() == null) {
          return;
        }

        // GitHub Actions support
        if ("true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))) {
          if (!this.getRev().equals(System.getenv("GITHUB_SHA"))) {
            return;
          }

          log.info("gather git situation from GitHub Actions environment variable: GITHUB_REF");
          String githubRef = System.getenv("GITHUB_REF");
          log.debug("  GITHUB_REF: {}", githubRef);

          if (githubRef.startsWith("refs/tags/")) {
            addTag(githubRef);
          } else {
            setBranch(githubRef);
          }
          return;
        }

        // GitLab CI support
        if ("true".equalsIgnoreCase(System.getenv("GITLAB_CI"))) {
          if (!this.getRev().equals(System.getenv("CI_COMMIT_SHA"))) {
            return;
          }

          log.info(
              "gather git situation from GitLab CI environment variables: CI_COMMIT_BRANCH,"
                  + " CI_MERGE_REQUEST_SOURCE_BRANCH_NAME and CI_COMMIT_TAG");
          String commitBranch = System.getenv("CI_COMMIT_BRANCH");
          String commitTag = System.getenv("CI_COMMIT_TAG");
          String mrSourceBranch = System.getenv("CI_MERGE_REQUEST_SOURCE_BRANCH_NAME");
          log.debug("  CI_COMMIT_BRANCH: {}", commitBranch);
          log.debug("  CI_COMMIT_TAG: {}", commitTag);
          log.debug("  CI_MERGE_REQUEST_SOURCE_BRANCH_NAME: {}", mrSourceBranch);

          if (commitBranch != null) {
            setBranch(commitBranch);
          } else if (mrSourceBranch != null) {
            setBranch(mrSourceBranch);
          } else if (commitTag != null) {
            addTag(commitTag);
          }
          return;
        }

        // Circle CI support
        if ("true".equalsIgnoreCase(System.getenv("CIRCLECI"))) {
          if (!this.getRev().equals(System.getenv("CIRCLE_SHA1"))) {
            return;
          }

          log.info(
              "gather git situation from Circle CI environment variables: CIRCLE_BRANCH and"
                  + " CIRCLE_TAG");
          String commitBranch = System.getenv("CIRCLE_BRANCH");
          String commitTag = System.getenv("CIRCLE_TAG");
          log.debug("  CIRCLE_BRANCH: {}", commitBranch);
          log.debug("  CIRCLE_TAG: {}", commitTag);

          if (commitBranch != null) {
            setBranch(commitBranch);
          } else if (commitTag != null) {
            addTag(commitTag);
          }
          return;
        }

        // Jenkins support
        if (System.getenv("JENKINS_HOME") != null
            && !System.getenv("JENKINS_HOME").trim().isEmpty()) {
          if (!this.getRev().equals(System.getenv("GIT_COMMIT"))) {
            return;
          }
          log.info(
              "gather git situation from jenkins environment variables: BRANCH_NAME and TAG_NAME");
          String commitBranch = System.getenv("BRANCH_NAME");
          String commitTag = System.getenv("TAG_NAME");
          log.debug("  BRANCH_NAME: {}", commitBranch);
          log.debug("  TAG_NAME: {}", commitTag);

          if (commitBranch != null) {
            if (commitBranch.equals(commitTag)) {
              addTag(commitBranch);
            } else {
              setBranch(commitBranch);
            }
          } else if (commitTag != null) {
            addTag(commitTag);
          }
          return;
        }
      }

      protected void setBranch(String branch) {
        log.debug("override git branch with {}", branch);
        super.setBranch(branch);
      }

      protected void setTags(@NonNull List<String> tags) {
        log.debug("override git tags with single tag {}", tags);
        super.setTags(tags);
      }

      protected void addTag(@NonNull String tag) {
        log.debug("add git tag {}", tag);
        super.addTag(tag);
      }
    };
  }

  private String getGitVersion(String versionFormat, String projectVersion) {
    return propsUtil.getGitVersion(versionFormat, projectVersion);
  }

  private String getGitPropertyValue(
      Object propertyFormat, String originalValue, String projectVersion) {
    return propsUtil.getGitPropertyValue(propertyFormat, originalValue, projectVersion);
  }

  // ---- configuration
  // -------------------------------------------------------------------------------------------------

  private String getCommandOption(final String name) {
    String value =
        sessionHolder
            .getGittleSession()
            .map(GittleSession::getUserProperties)
            .map(props -> props.getProperty(name))
            .orElse(null);
    if (value == null) {
      String plainName = name.replaceFirst("^versioning\\.", "");
      String environmentVariableName =
          "VERSIONING_"
              + String.join("_", plainName.split("(?=\\p{Lu})"))
                  .replaceAll("\\.", "_")
                  .toUpperCase();
      value = System.getenv(environmentVariableName);
    }
    if (value == null) {
      value = System.getProperty(name);
    }
    return value;
  }

  private boolean getUpdatePomOption(final PatchDescription gitRefConfig) {
    final String updatePomCommandOption = getCommandOption(OPTION_UPDATE_POM);
    if (updatePomCommandOption != null) {
      return parseBoolean(updatePomCommandOption);
    }

    //noinspection ReplaceNullCheck
    if (gitRefConfig.updatePom != null) {
      return gitRefConfig.updatePom;
    }

    return false;
  }

  private boolean getUseRevisionOption(final PatchDescription gitRefConfig) {
    final String useRevisionCommandOption = getCommandOption(OPTION_USE_REVISION);
    if (useRevisionCommandOption != null) {
      return parseBoolean(useRevisionCommandOption);
    }

    if (gitRefConfig.useRevision != null) {
      return gitRefConfig.useRevision;
    }

    if (config.useRevision != null) {
      return config.useRevision;
    }

    return false;
  }

  // ---- determine related projects
  // ---------------------------------------------------------------------------------

  private Set<Coords> determineRelatedProjects(Model projectModel) throws IOException {
    final HashSet<Coords> relatedProjects = new HashSet<>();
    determineRelatedProjects(projectModel, relatedProjects);
    config.relatedProjects.stream()
        .map(it -> Coords.of(it.groupId, it.artifactId, "*"))
        .forEach(relatedProjects::add);
    return relatedProjects;
  }

  private void determineRelatedProjects(Model projectModel, Set<Coords> relatedProjects)
      throws IOException {
    final Coords projectCoords = Coords.of(projectModel);
    if (relatedProjects.contains(projectCoords)) {
      return;
    }

    // add self
    relatedProjects.add(projectCoords);

    // check for related parent project by parent tag
    if (projectModel.getParent() != null) {
      final Coords parentCoords = Coords.of(projectModel.getParent());
      final File parentProjectPomFile = ProcessorUtil.getParentProjectPomFile(projectModel);
      if (isRelatedPom(parentProjectPomFile)) {
        final Model parentProjectModel = readModel(parentProjectPomFile);
        final Coords parentProjectCoords = Coords.of(parentProjectModel);
        if (parentProjectCoords.equals(parentCoords)) {
          determineRelatedProjects(parentProjectModel, relatedProjects);
        }
      }
    }

    // check for related parent project within parent directory
    final Model parentProjectModel = searchParentProjectInParentDirectory(projectModel);
    if (parentProjectModel != null) {
      determineRelatedProjects(parentProjectModel, relatedProjects);
    }

    //  process modules
    for (File modulePomFile : ProcessorUtil.getProjectModules(projectModel)) {
      Model moduleProjectModel = readModel(modulePomFile);
      determineRelatedProjects(moduleProjectModel, relatedProjects);
    }
  }

  private boolean isRelatedProject(Coords project) {
    return Stream.of(project, Coords.of(project.getGroupId(), project.getArtifactId(), "*"))
        .anyMatch(relatedProjects::contains);
  }

  /**
   * checks if <code>pomFile</code> is part of current maven and git context
   *
   * @param pomFile the pom file
   * @return true if <code>pomFile</code> is part of current maven and git context
   */
  private boolean isRelatedPom(File pomFile) throws IOException {
    return pomFile != null
        && pomFile.exists()
        && pomFile.isFile()
        // only project pom files ends in .xml, pom files from dependencies from repositories ends
        // in .pom
        && pomFile.getName().endsWith(".xml")
        && pomFile
            .getCanonicalPath()
            .startsWith(mvnDirectory.getParentFile().getCanonicalPath() + File.separator)
        // only pom files within git directory are treated as project pom files
        && pomFile
            .getCanonicalPath()
            .startsWith(gitSituation.getRootDirectory().getCanonicalPath() + File.separator);
  }

  private Model searchParentProjectInParentDirectory(Model projectModel) throws IOException {
    // search for parent project by directory hierarchy
    File parentDirectoryPomFile =
        pomFile(projectModel.getProjectDirectory().getParentFile(), "pom.xml");
    if (parentDirectoryPomFile.exists() && isRelatedPom(parentDirectoryPomFile)) {
      // check if parent has module that points to current project directory
      Model parentDirectoryProjectModel = readModel(parentDirectoryPomFile);
      for (File modulePomFile : ProcessorUtil.getProjectModules(parentDirectoryProjectModel)) {
        if (modulePomFile.getCanonicalFile().equals(projectModel.getPomFile().getCanonicalFile())) {
          return parentDirectoryProjectModel;
        }
      }
    }
    return null;
  }

  // ---- generate git versioned pom file
  // ----------------------------------------------------------------------------

  private File writePomFile(Model projectModel) throws IOException {
    Model model = projectModel.clone();

    File gitVersionedPomFile =
        new File(model.getProjectDirectory(), Constants.GIT_VERSIONING_POM_NAME);
    log.warn("generating (exists={}) {}", gitVersionedPomFile.isFile(), gitVersionedPomFile);

    model.getProperties().remove("revision");
    model.getProfiles().forEach(profile -> profile.getProperties().remove("revision"));

    // In case another ModelProcessor is used (like for example polyglot extension),
    // we need to work on the POM possibly translated into XML
    File locatedPom = locatePom(model.getProjectDirectory());
    Document gitVersionedPomDocument = readXml(locatedPom);
    Element projectElement = gitVersionedPomDocument.getChild("project");
    // update project
    ProcessorUtil.updateParentVersion(projectElement, model.getParent());
    ProcessorUtil.updateVersion(projectElement, model);
    updatePropertyValues(projectElement, model);

    ProcessorUtil.updateDependencyVersions(projectElement, model);
    ProcessorUtil.updatePluginVersions(projectElement, model.getBuild(), model.getReporting());

    updateProfiles(projectElement, model.getProfiles());

    // if (useRevision) {
    //   String pomXml = Files.readString(locatedPom.toPath(), UTF_8);
    //   pomXml = pomXml.replace("${revision}",
    // projectModel.getProperties().getProperty("revision"));
    //   log.warn("Writing: {}\n{}\n\n", gitVersionedPomFile.toPath(), pomXml);
    //   Files.writeString(gitVersionedPomFile.toPath(), pomXml, UTF_8, CREATE, TRUNCATE_EXISTING);
    // } else {
    writeXml(gitVersionedPomFile, gitVersionedPomDocument);
    // }

    return gitVersionedPomFile;
  }

  private void updatePropertyValues(Element element, ModelBase model) {
    // properties section
    Element propertiesElement = element.getChild("properties");
    if (propertiesElement == null) {
      return;
    }

    Optional.ofNullable(propertiesElement.getChild("revision"))
        .ifPresent(
            node -> {
              propertiesElement.removeNode(node);
              log.warn("Removed project revision property");
            });

    for (ListIterator<Element> iter = propertiesElement.getChildren().listIterator();
        iter.hasNext(); ) {
      Element propertyElement = iter.next();
      String propertyName = propertyElement.getName();

      if ("revision".equals(propertyName)) {
        log.warn("REMOVING {} property!", propertyName);
        iter.remove();
        continue;
      }

      String modelPropertyValue = model.getProperties().getProperty(propertyName);
      if (modelPropertyValue != null) {
        if (!Objects.equals(propertyElement.getText(), modelPropertyValue)) {
          propertyElement.setText(modelPropertyValue);
        }
      }
    }
  }

  private void updateProfiles(Element projectElement, List<Profile> profiles) {
    Element profilesElement = projectElement.getChild("profiles");
    if (profilesElement == null) {
      return;
    }
    Map<String, Profile> profileMap = profiles.stream().collect(toMap(Profile::getId, it -> it));
    for (Element profileElement : profilesElement.getChildren("profile")) {
      String profileId = profileElement.getChild("id").getText().trim();
      Profile profile = profileMap.get(profileId);
      updatePropertyValues(profileElement, profile);
      ProcessorUtil.updateDependencyVersions(profileElement, profile);
      ProcessorUtil.updatePluginVersions(
          profileElement, profile.getBuild(), profile.getReporting());
    }
  }
}
