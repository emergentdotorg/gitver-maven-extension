package lol.wud.gitver.maven;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toMap;
import static lol.wud.gitver.maven.Constants.GITVER_POM_NAME;
import static lol.wud.gitver.maven.MavenUtil.readXml;
import static lol.wud.gitver.maven.MavenUtil.writeXml;

import de.pdark.decentxml.Document;
import de.pdark.decentxml.Element;
import java.io.File;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.ModelBase;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.model.ReportPlugin;
import org.apache.maven.model.Reporting;

/**
 * Serializes an updated maven {@link Model} to a git versioned pom file by patching the values of
 * the original pom's XML, so formatting and comments are preserved.
 */
@Slf4j
final class PomXmlWriter {

  private PomXmlWriter() {}

  /**
   * Writes the git versioned pom next to the project, derived from the pom at <code>locatedPom
   * </code> and the already updated <code>projectModel</code>.
   *
   * @return the written file
   */
  static File write(Model projectModel, File locatedPom) {
    Model model = projectModel.clone();

    File versionedPomFile = new File(model.getProjectDirectory(), GITVER_POM_NAME);
    log.info("generating {}", versionedPomFile);

    model.getProperties().remove("revision");
    model.getProfiles().forEach(profile -> profile.getProperties().remove("revision"));

    Document versionedPomDoc = readXml(locatedPom);
    Element projectEl = versionedPomDoc.getChild("project");
    updateParentVersion(projectEl, model.getParent());
    updateVersion(projectEl, model);
    updatePropertyValues(projectEl, model);
    updateDependencyVersion(projectEl, model);
    updatePlugins(projectEl, model.getBuild(), model.getReporting());
    updateProfiles(projectEl, model.getProfiles());
    writeXml(versionedPomFile, versionedPomDoc);
    return versionedPomFile;
  }

  private static void updatePropertyValues(Element element, ModelBase model) {
    Element propertiesEl = element.getChild("properties");
    if (propertiesEl == null) {
      return;
    }

    // getChildren() returns a copy, so the revision property is removed through its parent
    ofNullable(propertiesEl.getChild("revision"))
        .ifPresent(
            node -> {
              propertiesEl.removeNode(node);
              log.debug("Removed project revision property");
            });

    for (Element propertyEl : propertiesEl.getChildren()) {
      ofNullable(model.getProperties().getProperty(propertyEl.getName()))
          .filter(v -> !v.equals(propertyEl.getText()))
          .ifPresent(propertyEl::setText);
    }
  }

  private static void updateProfiles(Element projectEl, List<Profile> profiles) {
    Element profilesEl = projectEl.getChild("profiles");
    if (profilesEl == null) {
      return;
    }
    Map<String, Profile> profileMap = profiles.stream().collect(toMap(Profile::getId, it -> it));
    for (Element profileEl : profilesEl.getChildren("profile")) {
      String profileId = profileEl.getChild("id").getText().trim();
      Profile profile = profileMap.get(profileId);
      if (profile == null) {
        throw new IllegalArgumentException("Unexpected xml profile without model: " + profileId);
      }
      updatePropertyValues(profileEl, profile);
      updateDependencyVersion(profileEl, profile);
      updatePlugins(profileEl, profile.getBuild(), profile.getReporting());
    }
  }

  private static void updateParentVersion(Element projectEl, Parent parent) {
    ofNullable(projectEl.getChild("parent"))
        .ifPresent(
            parentEl -> {
              Element parentVersionEl = parentEl.getChild("version");
              parentVersionEl.setText(parent.getVersion());
            });
  }

  private static void updateVersion(Element projectEl, Model projectModel) {
    ofNullable(projectEl.getChild("version"))
        .ifPresent(versionEl -> versionEl.setText(projectModel.getVersion()));
  }

  private static void updateDependencyVersion(Element element, ModelBase model) {
    ofNullable(element.getChild("dependencies"))
        .ifPresent(
            dependenciesEl -> updateDependencyVersion(dependenciesEl, model.getDependencies()));
    ofNullable(element.getChild("dependencyManagement"))
        .map(dmEl -> dmEl.getChild("dependencies"))
        .ifPresent(
            dependenciesEl ->
                updateDependencyVersion(
                    dependenciesEl, model.getDependencyManagement().getDependencies()));
  }

  private static void updateDependencyVersion(
      Element dependenciesEl, List<Dependency> dependencies) {
    forEachPair(
        dependenciesEl.getChildren(),
        dependencies,
        (dependencyEl, dependency) -> {
          if (!Objects.equals(
              dependency.getManagementKey(), getDependencyManagementKey(dependencyEl))) {
            throw new IllegalArgumentException(
                "Unexpected difference of xml and model dependencies order");
          }

          ofNullable(dependencyEl.getChild("version"))
              .ifPresent(
                  dependencyVersionEl -> {
                    dependencyVersionEl.setText(dependency.getVersion());
                  });
        });
  }

  private static String getDependencyManagementKey(Element element) {
    Element groupId = element.getChild("groupId");
    Element artifactId = element.getChild("artifactId");
    Element type = element.getChild("type");
    Element classifier = element.getChild("classifier");
    return (groupId != null ? groupId.getText().trim() : "")
        + ":"
        + (artifactId != null ? artifactId.getText().trim() : "")
        + ":"
        + (type != null ? type.getText().trim() : "jar")
        + (classifier != null ? ":" + classifier.getText().trim() : "");
  }

  private static void updatePlugins(Element projectEl, BuildBase build, Reporting reporting) {
    ofNullable(projectEl.getChild("build"))
        .ifPresent(
            buildEl -> {
              ofNullable(buildEl.getChild("plugins"))
                  .ifPresent(pluginsEl -> updatePlugins(pluginsEl, build.getPlugins()));
              ofNullable(buildEl.getChild("pluginManagement"))
                  .map(el -> el.getChild("plugins"))
                  .ifPresent(
                      pluginsEl ->
                          updatePlugins(pluginsEl, build.getPluginManagement().getPlugins()));
            });

    ofNullable(projectEl.getChild("reporting"))
        .map(reportingEl -> reportingEl.getChild("plugins"))
        .ifPresent(pluginsEl -> updateReportPluginVersions(pluginsEl, reporting.getPlugins()));
  }

  private static void updatePlugins(Element pluginsEl, List<Plugin> plugins) {
    forEachPair(
        pluginsEl.getChildren(),
        plugins,
        (pluginEl, plugin) -> {
          if (!Objects.equals(plugin.getKey(), getPluginKey(pluginEl))) {
            throw new IllegalArgumentException(
                "Unexpected difference of xml and model plugin order");
          }

          ofNullable(pluginEl.getChild("version"))
              .ifPresent(pluginVersionEl -> pluginVersionEl.setText(plugin.getVersion()));

          Element dependenciesEl = pluginEl.getChild("dependencies");
          List<Dependency> dependencies = plugin.getDependencies();
          if (!dependencies.isEmpty() && dependenciesEl != null) {
            updateDependencyVersion(dependenciesEl, dependencies);
          }
        });
  }

  private static void updateReportPluginVersions(Element pluginsEl, List<ReportPlugin> plugins) {
    forEachPair(
        pluginsEl.getChildren(),
        plugins,
        (pluginEl, plugin) -> {
          // sanity check
          if (!Objects.equals(plugin.getKey(), getPluginKey(pluginEl))) {
            throw new IllegalArgumentException(
                "Unexpected difference of xml and model report plugin order");
          }

          ofNullable(pluginEl.getChild("version"))
              .ifPresent(pluginVersionEl -> pluginVersionEl.setText(plugin.getVersion()));
        });
  }

  private static String getPluginKey(Element element) {
    Element groupId = element.getChild("groupId");
    Element artifactId = element.getChild("artifactId");
    return "%s:%s"
        .formatted(
            ofNullable(groupId).map(el -> el.getText().trim()).orElse("org.apache.maven.plugins"),
            ofNullable(artifactId).map(el1 -> el1.getText().trim()).orElse(""));
  }

  private static <T1, T2> void forEachPair(
      Collection<T1> collection1, Collection<T2> collection2, BiConsumer<T1, T2> consumer) {
    if (collection1.size() != collection2.size()) {
      throw new IllegalArgumentException("Collections sizes are not equals");
    }

    Iterator<T1> iter1 = collection1.iterator();
    Iterator<T2> iter2 = collection2.iterator();
    while (iter1.hasNext() && iter2.hasNext()) {
      consumer.accept(iter1.next(), iter2.next());
    }
  }
}
