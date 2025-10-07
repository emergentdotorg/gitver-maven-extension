package lol.wud.gitver.maven;

import static lol.wud.gitver.maven.MavenUtil.getBuild;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import lol.wud.gitver.git.GitSituation;
import lol.wud.gitver.maven.Configuration.RefConfig;
import lol.wud.gitver.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.ModelBase;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.model.Profile;
import org.apache.maven.model.ReportPlugin;
import org.apache.maven.model.Reporting;
import org.jspecify.annotations.Nullable;

/**
 * Applies calculated versions and git properties to the in-memory maven {@link Model}. Versions are
 * computed by the {@link VersionFormatter}; nothing here touches the file system.
 */
@Slf4j
final class ModelUpdater {

  private static final String REVISION = "revision";
  private static final String REVISION_PLACEHOLDER = "${" + REVISION + "}";

  private final VersionFormatter formatter;
  private final RelatedProjects related;
  private final GitSituation gitSituation;
  private final GitVersionDetails gitVersionDetails;
  private final GitverSession session;

  /** The first <code>revision</code> value seen, shown for modules that only inherit it. */
  private @Nullable String inheritedRevision;

  ModelUpdater(
      VersionFormatter formatter,
      RelatedProjects related,
      GitSituation gitSituation,
      GitVersionDetails gitVersionDetails,
      GitverSession session) {
    this.formatter = formatter;
    this.related = related;
    this.gitSituation = gitSituation;
    this.gitVersionDetails = gitVersionDetails;
    this.session = session;
  }

  /** Rewrites versions, properties and git properties of the in-memory model. */
  void update(Model projectModel) {
    RefConfig refConfig = gitVersionDetails.patchOptions();
    Coords originalCoords = Coords.of(projectModel);
    String originalVersion = originalCoords.getVersion();

    refConfig
        .getVersion()
        .ifPresent(
            versionFormat -> {
              log.info(
                  "Updating version {} -> {}",
                  originalCoords.toBuilder().setVersion(displayVersion(projectModel)).build(),
                  formatter.formatVersion(versionFormat, originalVersion));
              updateParentVersion(projectModel, versionFormat);
              setFormattedVersion(projectModel::setVersion, originalCoords, versionFormat);
              updateDependencyVersions(projectModel, versionFormat);
              updatePluginVersions(projectModel, versionFormat);
            });

    updatePropertyValues(projectModel, refConfig.getProperties(), originalVersion);
    addGitProperties(projectModel);
    updateProfiles(projectModel, refConfig, originalVersion);
  }

  /**
   * The project version to log. A <code>${revision}</code> version is shown resolved: from the
   * session, the model, or else the value of a project processed earlier, typically the parent.
   */
  private String displayVersion(Model projectModel) {
    String version = Coords.of(projectModel).getVersion();
    if (!REVISION_PLACEHOLDER.equals(version)) {
      return version;
    }
    String revision =
        Stream.of(
                session.getUserProperties(),
                session.getSystemProperties(),
                projectModel.getProperties())
            .map(properties -> properties.getProperty(REVISION))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(inheritedRevision);
    if (revision == null) {
      log.warn("no value found for {} in {}", REVISION_PLACEHOLDER, projectModel.getPomFile());
      return version;
    }
    if (inheritedRevision == null) {
      inheritedRevision = revision;
    }
    return revision;
  }

  private void updateProfiles(Model model, RefConfig refConfig, String projectVersion) {
    for (Profile profile : model.getProfiles()) {
      refConfig
          .getVersion()
          .ifPresent(
              versionFormat -> {
                updateDependencyVersions(profile, versionFormat);
                updatePluginVersions(profile, versionFormat);
              });
      updatePropertyValues(profile, refConfig.getProperties(), projectVersion);
    }
  }

  private void updateParentVersion(Model projectModel, String versionFormat) {
    if (projectModel.getParent() != null) {
      setFormattedVersion(
          projectModel.getParent()::setVersion, Coords.of(projectModel.getParent()), versionFormat);
    }
  }

  private void updatePropertyValues(
      ModelBase model, Map<String, String> propertyFormats, String projectVersion) {
    Properties properties = model.getProperties();
    propertyFormats.forEach(
        (name, propertyFormat) -> {
          String value = properties.getProperty(name);
          if (value == null) {
            return;
          }
          String gitValue = formatter.formatProperty(propertyFormat, value, projectVersion);
          if (!gitValue.equals(value)) {
            log.info("set property {} to {}", name, gitValue);
            properties.setProperty(name, gitValue);
          }
        });
  }

  private void updateDependencyVersions(ModelBase model, String versionFormat) {
    updateVersions("dependencies", model, model.getDependencies(), versionFormat);
    DependencyManagement dependencyManagement = model.getDependencyManagement();
    if (dependencyManagement != null) {
      updateVersions(
          "dependency management", model, dependencyManagement.getDependencies(), versionFormat);
    }
  }

  private void updatePluginVersions(ModelBase model, String versionFormat) {
    BuildBase build = getBuild(model);
    if (build == null) {
      return;
    }
    updatePlugins("plugins", model, build.getPlugins(), versionFormat);
    PluginManagement pluginManagement = build.getPluginManagement();
    if (pluginManagement != null) {
      updatePlugins("plugin management", model, pluginManagement.getPlugins(), versionFormat);
    }
    Reporting reporting = model.getReporting();
    if (reporting != null) {
      updateVersions(
          "reporting plugins",
          model,
          reporting.getPlugins(),
          Coords::of,
          ReportPlugin::setVersion,
          versionFormat);
    }
  }

  private void updatePlugins(
      String section, ModelBase model, List<Plugin> plugins, String versionFormat) {
    updateVersions(section, model, plugins, Coords::of, Plugin::setVersion, versionFormat);
    List<Dependency> dependencies =
        plugins.stream().flatMap(plugin -> plugin.getDependencies().stream()).toList();
    updateVersions(section + " dependencies", model, dependencies, versionFormat);
  }

  private void updateVersions(
      String section, ModelBase model, List<Dependency> dependencies, String versionFormat) {
    updateVersions(section, model, dependencies, Coords::of, Dependency::setVersion, versionFormat);
  }

  /** Sets the formatted version of each element that belongs to a related project. */
  private <T> void updateVersions(
      String section,
      ModelBase model,
      List<T> elements,
      Function<T, Coords> coordsOf,
      BiConsumer<T, String> versionSetter,
      String versionFormat) {
    List<T> relatedElements =
        elements.stream().filter(it -> related.contains(coordsOf.apply(it))).toList();
    if (relatedElements.isEmpty()) {
      return;
    }
    if (log.isDebugEnabled()) {
      log.debug(LogHeaders.section(section, model));
    }
    for (T element : relatedElements) {
      setFormattedVersion(
          version -> versionSetter.accept(element, version),
          coordsOf.apply(element),
          versionFormat);
    }
  }

  private void setFormattedVersion(
      Consumer<String> versionSetter, Coords coords, String versionFormat) {
    String version = coords.getVersion();
    if (StringUtil.isNotBlank(version)) {
      String gitVersion = formatter.formatVersion(versionFormat, version);
      log.debug("{}: set version to {}", coords.getProjectId(), gitVersion);
      versionSetter.accept(gitVersion);
    }
  }

  private void addGitProperties(Model projectModel) {
    Properties properties = projectModel.getProperties();
    String commit = gitVersionDetails.commit();
    ZonedDateTime commitTime = gitSituation.getTimestamp();
    String refName = gitVersionDetails.refName();

    properties.putIfAbsent("git.worktree", gitSituation.getRootDirectory().getAbsolutePath());
    properties.putIfAbsent("git.commit", commit);
    properties.putIfAbsent("git.commit.short", commit.substring(0, 7));
    properties.putIfAbsent("git.commit.timestamp", String.valueOf(commitTime.toEpochSecond()));
    properties.putIfAbsent(
        "git.commit.timestamp.datetime", VersionPlaceholders.isoTimestamp(commitTime));
    properties.putIfAbsent("git.ref", refName);
    properties.putIfAbsent("git.ref.slug", VersionUtil.slugify(refName));
  }
}
