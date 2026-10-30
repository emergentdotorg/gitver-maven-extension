package org.emergent.gitver.maven;

import static java.time.format.DateTimeFormatter.ISO_INSTANT;
import static java.util.stream.Collectors.toList;
import static org.emergent.gitver.maven.MavenUtil.getBuild;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.ModelBase;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.model.ReportPlugin;
import org.emergent.gitver.git.GitSituation;
import org.emergent.gitver.maven.Configuration.RefConfig;
import org.emergent.gitver.util.StringUtil;

/**
 * Applies calculated versions and git properties to the in-memory maven {@link Model}. Versions are
 * computed by the {@link VersionFormatter}; nothing here touches the file system.
 */
@Slf4j
final class ModelUpdater {

  private final VersionFormatter formatter;
  private final RelatedProjects related;
  private final GitSituation gitSituation;
  private final GitVersionDetails gitVersionDetails;
  private final GitverSession session;

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
    RefConfig patchConfig = gitVersionDetails.patchOptions();
    Coords originalProjectCoords = Coords.of(projectModel);

    Optional<String> patchConfigVersion = patchConfig.getVersion();
    patchConfigVersion.ifPresent(
        versionFormat -> {
          log.info("Hello versionFormat {}", versionFormat);
          String originalVer = originalProjectCoords.getVersion();
          log.info("Hello originalVer {}", originalVer);
          String resolvedVer = formatVersion(versionFormat, originalVer);
          log.info("Resolved revision from formatVersion(originalVer) : {}", resolvedVer);

          if ("${revision}".equals(originalVer)) {
            // userProperties.put("revision", formatVersion(versionFormat, "${revision}"));
            String revision;
            revision = session.getUserProperties().getProperty("revision");
            if (revision != null) {
              log.info("Resolved revision from user properties : {}", revision);
            } else {
              revision = session.getSystemProperties().getProperty("revision");
              if (revision != null) {
                log.info("Resolved revision from system properties : {}", revision);
              } else {
                revision = projectModel.getProperties().getProperty("revision");
                if (revision != null) {
                  log.info("Resolved revision from project model properties : {}", revision);
                } else {
                  revision = session.getUserProperties().getProperty("revision.original");
                  if (revision != null) {
                    log.info(
                        "Resolved revision from revision.original user property : {}", revision);
                  }
                }
              }
            }

            if (revision == null) {
              log.warn("GITVER: No revision found in project properties!");
            } else {
              session.getUserProperties().putIfAbsent("revision.original", revision);
              originalVer = revision;
            }

            // Map<Coords, Properties> propsByCoords = session.getCoordsProperties();
            // Properties projectProps = propsByCoords.get(originalProjectCoords.sansVersion());
            // String ogRevision = projectProps.getProperty("revision");
            // Optional.ofNullable(projectModel.getProperties().getProperty("revision"))
            //    .ifPresent(rev -> session.getUserProperties()
            //        .putIfAbsent("revision.original", rev));
            // String origRevision =
            // session.getUserProperties().getProperty("revision.original");
            // if (origRevision != null) {
            //  oldVersion = origRevision;
            // }
          }

          log.info(
              "Updating version {} -> {}",
              originalProjectCoords.toBuilder().setVersion(originalVer),
              resolvedVer);

          updateParentVersion(projectModel, versionFormat);
          updateVersion(projectModel, versionFormat);
          updateDependencyVersions(projectModel, versionFormat);
          updatePluginVersions(projectModel, versionFormat);
        });

    Map<String, String> propertyFormats = patchConfig.getProperties();
    updatePropertyValues(projectModel, propertyFormats, originalProjectCoords);
    addProjectProperties(projectModel);
    updateProfiles(projectModel, patchConfig, originalProjectCoords);
  }

  private void updateProfiles(
      Model model, RefConfig patchDescription, Coords originalProjectCoords) {
    List<Profile> profiles = model.getProfiles();

    // profile section
    if (!profiles.isEmpty()) {
      for (Profile profile : profiles) {
        patchDescription
            .getVersion()
            .ifPresent(
                version -> {
                  updateDependencyVersions(profile, version);
                  updatePluginVersions(profile, version);
                });
        Map<String, String> propertyFormats = patchDescription.getProperties();
        updatePropertyValues(profile, propertyFormats, originalProjectCoords);
      }
    }
  }

  private void updateParentVersion(Model projectModel, String versionFormat) {
    Optional.ofNullable(projectModel.getParent())
        .ifPresent(parent -> updateVersion(parent::setVersion, versionFormat, Coords.of(parent)));
  }

  private void updateVersion(Model projectModel, String versionFormat) {
    updateVersion(projectModel::setVersion, versionFormat, Coords.of(projectModel));
  }

  private void updatePropertyValues(
      ModelBase model, Map<String, String> propertyFormats, Coords originalProjectCoords) {
    Properties modelProps = model.getProperties();
    if (propertyFormats.isEmpty()) {
      return;
    }
    // properties section
    modelProps.forEach(
        (modelPropertyName, modelPropertyValue) -> {
          Optional.ofNullable(propertyFormats.get((String) modelPropertyName))
              .ifPresent(
                  propertyFormat -> {
                    String gitPropertyValue =
                        formatProperty(
                            propertyFormat,
                            (String) modelPropertyValue,
                            originalProjectCoords.getVersion());
                    if (!gitPropertyValue.equals(modelPropertyValue)) {
                      log.info("set property {} to {}", modelPropertyName, gitPropertyValue);
                      model.addProperty((String) modelPropertyName, gitPropertyValue);
                    }
                  });
        });
  }

  private void updatePluginVersions(ModelBase model, String versionFormat) {
    BuildBase build = getBuild(model);
    if (build == null) {
      return;
    }
    Optional.of(filterRelatedPlugins(build.getPlugins()))
        .filter(col -> !col.isEmpty())
        .ifPresent(
            related -> {
              if (log.isDebugEnabled()) {
                log.debug(LogHeaders.section("plugins", model));
              }
              related.forEach(plugin -> updateVersion(plugin, versionFormat));
            });
    updatePluginDependencyVersions(model, versionFormat, build.getPlugins());

    // plugin management section
    Optional.ofNullable(build.getPluginManagement())
        .ifPresent(
            pluginManagement -> {
              Optional.of(filterRelatedPlugins(pluginManagement.getPlugins()))
                  .filter(col -> !col.isEmpty())
                  .ifPresent(
                      related -> {
                        if (log.isDebugEnabled()) {
                          log.debug(LogHeaders.section("plugin management", model));
                        }
                        related.forEach(plugin -> updateVersion(plugin, versionFormat));
                      });
              updatePluginDependencyVersions(model, versionFormat, build.getPlugins());
            });

    // reporting section
    Optional.ofNullable(model.getReporting())
        .flatMap(
            reporting ->
                Optional.of(filterRelatedReportPlugins(reporting.getPlugins()))
                    .filter(col -> !col.isEmpty()))
        .ifPresent(
            related -> {
              if (log.isDebugEnabled()) {
                log.debug(LogHeaders.section("reporting plugins", model));
              }
              related.forEach(plugin -> updateVersion(plugin, versionFormat));
            });
  }

  private void updatePluginDependencyVersions(
      ModelBase model, String versionFormat, List<Plugin> plugins) {
    List<Dependency> dependencies =
        plugins.stream().map(Plugin::getDependencies).flatMap(List::stream).collect(toList());
    Optional.of(filterRelatedDependencies(dependencies))
        .filter(col -> !col.isEmpty())
        .ifPresent(
            related -> {
              if (log.isDebugEnabled()) {
                log.debug(LogHeaders.section("plugins dependencies", model));
              }
              related.forEach(dep -> updateVersion(dep, versionFormat));
            });
  }

  private void updateVersion(Plugin plugin, String versionFormat) {
    updateVersion(plugin::setVersion, versionFormat, Coords.of(plugin));
  }

  private void updateVersion(Consumer<String> consumer, String versionFormat, Coords coords) {
    String version = coords.getVersion();
    if (StringUtil.isNotBlank(version)) {
      String gitVersion = formatVersion(versionFormat, version);
      log.debug("{}: set version to {}", coords.getProjectId(), gitVersion);
      consumer.accept(gitVersion);
    }
  }

  private void updateVersion(ReportPlugin plugin, String versionFormat) {
    updateVersion(plugin::setVersion, versionFormat, Coords.of(plugin));
  }

  private void updateDependencyVersions(ModelBase model, String versionFormat) {
    // dependencies section
    Optional.of(filterRelatedDependencies(model.getDependencies()))
        .filter(col -> !col.isEmpty())
        .ifPresent(
            related -> {
              if (log.isDebugEnabled()) {
                log.debug(LogHeaders.section("dependencies", model));
              }
              related.forEach(dependency -> updateVersion(dependency, versionFormat));
            });

    // dependency management section
    Optional.ofNullable(model.getDependencyManagement())
        .flatMap(
            dependencyManagement ->
                Optional.of(filterRelatedDependencies(dependencyManagement.getDependencies()))
                    .filter(col -> !col.isEmpty()))
        .ifPresent(
            related -> {
              if (log.isDebugEnabled()) {
                log.debug(LogHeaders.section("dependency management", model));
              }
              related.forEach(dependency -> updateVersion(dependency, versionFormat));
            });
  }

  private void updateVersion(Dependency dependency, String versionFormat) {
    updateVersion(dependency::setVersion, versionFormat, Coords.of(dependency));
  }

  private void addProjectProperties(Model projectModel) {
    Properties projectProperties = projectModel.getProperties();

    if (!projectProperties.containsKey("git.worktree")) {
      projectModel.addProperty("git.worktree", gitSituation.getRootDirectory().getAbsolutePath());
    }

    if (!projectProperties.containsKey("git.commit")) {
      projectModel.addProperty("git.commit", gitVersionDetails.commit());
    }
    if (!projectProperties.containsKey("git.commit.short")) {
      projectModel.addProperty("git.commit.short", gitVersionDetails.commit().substring(0, 7));
    }

    ZonedDateTime headCommitDateTime = gitSituation.getTimestamp();
    if (!projectProperties.containsKey("git.commit.timestamp")) {
      projectModel.addProperty(
          "git.commit.timestamp", String.valueOf(headCommitDateTime.toEpochSecond()));
    }
    if (!projectProperties.containsKey("git.commit.timestamp.datetime")) {
      projectModel.addProperty(
          "git.commit.timestamp.datetime",
          headCommitDateTime.toEpochSecond() > 0
              ? headCommitDateTime.format(ISO_INSTANT)
              : "0000-00-00T00:00:00Z");
    }

    String refName = gitVersionDetails.refName();
    if (!projectProperties.containsKey("git.ref")) {
      projectModel.addProperty("git.ref", refName);
    }
    if (!projectProperties.containsKey("git.ref.slug")) {
      projectModel.addProperty("git.ref.slug", VersionUtil.slugify(refName));
    }
  }

  private List<Plugin> filterRelatedPlugins(List<Plugin> plugins) {
    return plugins.stream().filter(it -> related.contains(Coords.of(it))).collect(toList());
  }

  private List<ReportPlugin> filterRelatedReportPlugins(List<ReportPlugin> plugins) {
    return plugins.stream().filter(it -> related.contains(Coords.of(it))).collect(toList());
  }

  private List<Dependency> filterRelatedDependencies(List<Dependency> dependencies) {
    return dependencies.stream().filter(it -> related.contains(Coords.of(it))).collect(toList());
  }

  private String formatVersion(String versionFormat, String projectVersion) {
    return formatter.formatVersion(versionFormat, projectVersion);
  }

  private String formatProperty(
      Object propertyFormat, String originalValue, String projectVersion) {
    return formatter.formatProperty(propertyFormat, originalValue, projectVersion);
  }
}
