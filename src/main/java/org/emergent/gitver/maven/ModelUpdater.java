package org.emergent.gitver.maven;

import static java.time.format.DateTimeFormatter.ISO_INSTANT;
import static java.util.stream.Collectors.toList;
import static org.emergent.gitver.maven.MavenUtil.getBuild;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
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
import org.emergent.gitver.maven.Configuration.RefPatchDescription;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

/**
 * Applies calculated versions and git properties to the in-memory maven {@link Model}. Versions are
 * computed by the {@link VersionFormatter}; nothing here touches the file system.
 */
@Slf4j
@NullUnmarked
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
    final RefPatchDescription patchDescription = gitVersionDetails.patchDescription();
    Coords originalProjectCoords = Coords.of(projectModel);

    final String versionFormat = patchDescription.getVersion();
    if (versionFormat != null) {
      String oldVersionInput = originalProjectCoords.getVersion();
      if ("${revision}".equals(oldVersionInput)) {
        Properties projectProperties = projectModel.getProperties();
        Properties userProperties = session.getUserProperties();

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
          formatter.formatVersion(versionFormat, originalProjectCoords.getVersion()));

      updateParentVersion(projectModel, versionFormat);
      updateVersion(projectModel, versionFormat);
      updateDependencyVersions(projectModel, versionFormat);
      updatePluginVersions(projectModel, versionFormat);
    }

    Map<String, String> propertyFormats = patchDescription.getProperties();
    updatePropertyValues(projectModel, propertyFormats, originalProjectCoords);
    addProjectProperties(projectModel);

    updateProfiles(projectModel, patchDescription, originalProjectCoords);
  }

  private void updateProfiles(
      Model model, RefPatchDescription patchDescription, Coords originalProjectCoords) {
    List<Profile> profiles = model.getProfiles();

    // profile section
    if (!profiles.isEmpty()) {
      for (Profile profile : profiles) {
        String version = patchDescription.getVersion();
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
      if (related.contains(parentCoords)) {
        String gitVersion = formatter.formatVersion(versionFormat, parentCoords.getVersion());
        log.debug("set parent version to {} ({})", gitVersion, parentCoords);
        parent.setVersion(gitVersion);
      }
    }
  }

  private void updateVersion(Model projectModel, String versionFormat) {
    if (projectModel.getVersion() != null) {
      Coords projectCoords = Coords.of(projectModel);
      String gitVersion = formatter.formatVersion(versionFormat, projectCoords.getVersion());
      log.info("set version to {}", gitVersion);
      projectModel.setVersion(gitVersion);
      session.getUserProperties().put("revision", gitVersion);
    }
  }

  private void updatePropertyValues(
      ModelBase model,
      @Nullable Map<String, String> propertyFormats,
      Coords originalProjectCoords) {
    Properties modelProps = model.getProperties();
    if (Objects.isNull(propertyFormats) || propertyFormats.isEmpty()) {
      return;
    }
    // properties section
    modelProps.forEach(
        (modelPropertyName, modelPropertyValue) -> {
          String propertyFormat = propertyFormats.get((String) modelPropertyName);
          if (propertyFormat != null) {
            String gitPropertyValue =
                formatter.formatProperty(
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
          log.debug(LogHeaders.section("plugins", model));
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
          log.debug(LogHeaders.section("plugin management", model));
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
          log.debug(LogHeaders.section("reporting plugins", model));
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
        log.debug(LogHeaders.section("plugins dependencies", model));
      }
      for (Dependency dep : pluginDepsList) {
        updateVersion(dep, versionFormat);
      }
    }
  }

  private void updateVersion(Plugin plugin, String versionFormat) {
    if (plugin.getVersion() != null) {
      Coords pluginCoords = Coords.of(plugin);
      String gitVersion = formatter.formatVersion(versionFormat, pluginCoords.getVersion());
      if (log.isDebugEnabled()) {
        log.debug("{}: set version to {}", pluginCoords.getProjectId(), gitVersion);
      }
      plugin.setVersion(gitVersion);
    }
  }

  private void updateVersion(ReportPlugin plugin, String versionFormat) {
    if (plugin.getVersion() != null) {
      Coords pluginCoords = Coords.of(plugin);
      String gitVersion = formatter.formatVersion(versionFormat, pluginCoords.getVersion());
      if (log.isDebugEnabled()) {
        log.debug("{}: set version to {}", pluginCoords.getProjectId(), gitVersion);
      }
      plugin.setVersion(gitVersion);
    }
  }

  private List<Plugin> filterRelatedPlugins(List<Plugin> plugins) {
    return plugins.stream().filter(it -> related.contains(Coords.of(it))).collect(toList());
  }

  private List<ReportPlugin> filterRelatedReportPlugins(List<ReportPlugin> plugins) {
    return plugins.stream().filter(it -> related.contains(Coords.of(it))).collect(toList());
  }

  private void updateDependencyVersions(ModelBase model, String versionFormat) {
    // dependencies section
    {
      List<Dependency> relatedDependencies = filterRelatedDependencies(model.getDependencies());
      if (!relatedDependencies.isEmpty()) {
        if (log.isDebugEnabled()) {
          log.debug(LogHeaders.section("dependencies", model));
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
          log.debug(LogHeaders.section("dependency management", model));
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
      String gitVersion = formatter.formatVersion(versionFormat, dependencyCoords.getVersion());
      if (log.isDebugEnabled()) {
        log.debug("{}: set version to {}", dependencyCoords.getProjectId(), gitVersion);
      }
      dependency.setVersion(gitVersion);
    }
  }

  private List<Dependency> filterRelatedDependencies(List<Dependency> dependencies) {
    return dependencies.stream().filter(it -> related.contains(Coords.of(it))).collect(toList());
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
      projectModel.addProperty("git.ref.slug", VersionUtil.slugify(refName));
    }
  }
}
