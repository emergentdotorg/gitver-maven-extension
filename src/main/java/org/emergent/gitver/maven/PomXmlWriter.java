package org.emergent.gitver.maven;

import static java.util.stream.Collectors.toMap;
import static org.emergent.gitver.maven.MavenUtil.readXml;
import static org.emergent.gitver.maven.MavenUtil.writeXml;

import de.pdark.decentxml.Document;
import de.pdark.decentxml.Element;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import org.jspecify.annotations.NullUnmarked;

/**
 * Serializes an updated maven {@link Model} to a git versioned pom file by patching the values of
 * the original pom's XML, so formatting and comments are preserved.
 */
@Slf4j
@NullUnmarked
final class PomXmlWriter {

  private PomXmlWriter() {}

  /**
   * Writes the git versioned pom next to the project, derived from the pom at <code>locatedPom
   * </code> and the already updated <code>projectModel</code>.
   *
   * @return the written file
   */
  static File write(Model projectModel, File locatedPom) throws IOException {
    Model model = projectModel.clone();

    File gitVersionedPomFile =
        new File(model.getProjectDirectory(), Constants.GIT_VERSIONING_POM_NAME);
    log.warn("generating (exists={}) {}", gitVersionedPomFile.isFile(), gitVersionedPomFile);

    model.getProperties().remove("revision");
    model.getProfiles().forEach(profile -> profile.getProperties().remove("revision"));

    Document gitVersionedPomDocument = readXml(locatedPom);
    Element projectElement = gitVersionedPomDocument.getChild("project");
    // update project
    updateParentVersion(projectElement, model.getParent());
    updateVersion(projectElement, model);
    updatePropertyValues(projectElement, model);

    updateDependencyVersions(projectElement, model);
    updatePluginVersions(projectElement, model.getBuild(), model.getReporting());

    updateProfiles(projectElement, model.getProfiles());

    writeXml(gitVersionedPomFile, gitVersionedPomDocument);

    return gitVersionedPomFile;
  }

  private static void updatePropertyValues(Element element, ModelBase model) {
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

  private static void updateProfiles(Element projectElement, List<Profile> profiles) {
    Element profilesElement = projectElement.getChild("profiles");
    if (profilesElement == null) {
      return;
    }
    Map<String, Profile> profileMap = profiles.stream().collect(toMap(Profile::getId, it -> it));
    for (Element profileElement : profilesElement.getChildren("profile")) {
      String profileId = profileElement.getChild("id").getText().trim();
      Profile profile = profileMap.get(profileId);
      updatePropertyValues(profileElement, profile);
      updateDependencyVersions(profileElement, profile);
      updatePluginVersions(profileElement, profile.getBuild(), profile.getReporting());
    }
  }

  private static void updateParentVersion(Element projectElement, Parent parent) {
    Element parentElement = projectElement.getChild("parent");
    if (parentElement != null) {
      Element parentVersionElement = parentElement.getChild("version");
      parentVersionElement.setText(parent.getVersion());
    }
  }

  private static void updateVersion(Element projectElement, Model projectModel) {
    Element versionElement = projectElement.getChild("version");
    if (versionElement != null) {
      versionElement.setText(projectModel.getVersion());
    }
  }

  private static void updateDependencyVersions(Element element, ModelBase model) {
    // dependencies section
    {
      Element dependenciesElement = element.getChild("dependencies");
      if (dependenciesElement != null) {
        updateDependencyVersions(dependenciesElement, model.getDependencies());
      }
    }
    // dependencyManagement section
    Element dependencyManagementElement = element.getChild("dependencyManagement");
    if (dependencyManagementElement != null) {
      Element dependenciesElement = dependencyManagementElement.getChild("dependencies");
      if (dependenciesElement != null) {
        updateDependencyVersions(
            dependenciesElement, model.getDependencyManagement().getDependencies());
      }
    }
  }

  private static void updateDependencyVersions(
      Element dependenciesElement, List<Dependency> dependencies) {
    forEachPair(
        dependenciesElement.getChildren(),
        dependencies,
        (dependencyElement, dependency) -> {
          // sanity check
          if (!Objects.equals(
              dependency.getManagementKey(), getDependencyManagementKey(dependencyElement))) {
            throw new IllegalArgumentException(
                "Unexpected difference of xml and model dependencies order");
          }

          Element dependencyVersionElement = dependencyElement.getChild("version");
          if (dependencyVersionElement != null) {
            dependencyVersionElement.setText(dependency.getVersion());
          }
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

  private static void updatePluginVersions(
      Element projectElement, BuildBase build, Reporting reporting) {
    // build section
    Element buildElement = projectElement.getChild("build");
    if (buildElement != null) {
      // plugins section
      {
        Element pluginsElement = buildElement.getChild("plugins");
        if (pluginsElement != null) {
          updatePluginVersions(pluginsElement, build.getPlugins());
        }
      }
      // pluginManagement section
      Element pluginsManagementElement = buildElement.getChild("pluginsManagement");
      if (pluginsManagementElement != null) {
        Element pluginsElement = pluginsManagementElement.getChild("plugins");
        if (pluginsElement != null) {
          updatePluginVersions(pluginsElement, build.getPluginManagement().getPlugins());
        }
      }
    }

    Element reportingElement = projectElement.getChild("reporting");
    if (reportingElement != null) {
      // plugins section
      {
        Element pluginsElement = reportingElement.getChild("plugins");
        if (pluginsElement != null) {
          updateReportPluginVersions(pluginsElement, reporting.getPlugins());
        }
      }
    }
  }

  private static void updatePluginVersions(Element pluginsElement, List<Plugin> plugins) {
    forEachPair(
        pluginsElement.getChildren(),
        plugins,
        (pluginElement, plugin) -> {
          // sanity check
          if (!Objects.equals(plugin.getKey(), getPluginKey(pluginElement))) {
            throw new IllegalArgumentException(
                "Unexpected difference of xml and model plugin order");
          }

          Element pluginVersionElement = pluginElement.getChild("version");
          if (pluginVersionElement != null) {
            pluginVersionElement.setText(plugin.getVersion());
          }

          Element dependenciesElement = pluginElement.getChild("dependencies");
          List<Dependency> dependencies = plugin.getDependencies();
          if (!dependencies.isEmpty() && dependenciesElement != null) {
            updateDependencyVersions(dependenciesElement, dependencies);
          }
        });
  }

  private static void updateReportPluginVersions(
      Element pluginsElement, List<ReportPlugin> plugins) {
    forEachPair(
        pluginsElement.getChildren(),
        plugins,
        (pluginElement, plugin) -> {
          // sanity check
          if (!Objects.equals(plugin.getKey(), getPluginKey(pluginElement))) {
            throw new IllegalArgumentException(
                "Unexpected difference of xml and model report plugin order");
          }

          Element pluginVersionElement = pluginElement.getChild("version");
          if (pluginVersionElement != null) {
            pluginVersionElement.setText(plugin.getVersion());
          }
        });
  }

  private static String getPluginKey(Element element) {
    Element groupId = element.getChild("groupId");
    Element artifactId = element.getChild("artifactId");
    return (groupId != null ? groupId.getText().trim() : "org.apache.maven.plugins")
        + ":"
        + (artifactId != null ? artifactId.getText().trim() : "");
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
