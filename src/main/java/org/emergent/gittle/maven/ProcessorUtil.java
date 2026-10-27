package org.emergent.gittle.maven;

import static java.lang.Math.ceil;
import static java.lang.Math.floor;
import static java.lang.Math.max;
import static java.util.stream.Collectors.toSet;
import static org.apache.maven.shared.utils.StringUtils.repeat;
import static org.apache.maven.shared.utils.logging.MessageUtils.buffer;
import static org.emergent.gittle.maven.GitRefType.BRANCH;
import static org.emergent.gittle.maven.GitRefType.COMMIT;
import static org.emergent.gittle.maven.GitRefType.TAG;
import static org.emergent.gittle.maven.MavenUtil.pomFile;

import com.google.common.io.ByteStreams;
import de.pdark.decentxml.Element;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.ModelBase;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.model.ReportPlugin;
import org.apache.maven.model.Reporting;
import org.emergent.gittle.maven.Configuration.RefPatchDescription;
import org.jspecify.annotations.NullUnmarked;

class ProcessorUtil {

  @NullUnmarked
  static GitVersionDetails getGitVersionDetails(GitSituation gitSituation, Configuration config) {
    final Lazy<List<String>> sortedTags = Lazy.by(gitSituation::getTags);
    for (RefPatchDescription refConfig : config.getRefs()) {
      if (refConfig.updateAttachedPom == null)
        refConfig.updateAttachedPom = config.getUpdateAttachedPom();
      switch (refConfig.type) {
        case TAG:
          {
            if (gitSituation.isDetached() || config.getConsiderTagsOnBranches()) {
              for (String tag : sortedTags.get()) {
                Pattern pattern =
                    Optional.ofNullable(refConfig.pattern).map(Pattern::compile).orElse(null);
                if (pattern == null || pattern.matcher(tag).matches()) {
                  return new GitVersionDetails(gitSituation.getRev(), TAG, tag, refConfig);
                }
              }
            }
          }
          break;
        case BRANCH:
          {
            if (!gitSituation.isDetached()) {
              String branch = gitSituation.getBranch();
              Pattern pattern =
                  Optional.ofNullable(refConfig.pattern).map(Pattern::compile).orElse(null);
              if (pattern == null || pattern.matcher(branch).matches()) {
                return new GitVersionDetails(gitSituation.getRev(), BRANCH, branch, refConfig);
              }
            }
          }
          break;
        default:
          throw new IllegalArgumentException("Unexpected ref type: " + refConfig.type);
      }
    }

    if (config.getRev() != null) {
      if (config.getRev().updateAttachedPom == null)
        config.getRev().updateAttachedPom = config.getUpdateAttachedPom();
      return new GitVersionDetails(
          gitSituation.getRev(),
          COMMIT,
          gitSituation.getRev(),
          new RefPatchDescription(COMMIT, null, config.getRev()));
    }

    return null;
  }

  @NullUnmarked
  static File findMvnDirectory(File pomFile) throws IOException {
    File searchDirectory = pomFile.getParentFile();
    while (searchDirectory != null) {
      File mvnDir = new File(searchDirectory, ".mvn");
      if (mvnDir.exists()) {
        return mvnDir;
      }
      searchDirectory = searchDirectory.getParentFile();
    }

    throw new FileNotFoundException("Can not find .mvn directory in hierarchy of " + pomFile);
  }

  @NullUnmarked
  static Configuration readConfig(File configFile) throws IOException {
    return readConfig(configFile.toPath());
  }

  static Configuration readConfig(Path configFile) throws IOException {
    if (!Files.isRegularFile(configFile)) {
      return Configuration.getDefault();
    }
    return readConfig(Files.readString(configFile, StandardCharsets.UTF_8));
  }

  static Configuration readConfig(URL resource) throws IOException {
    try (InputStream is = resource.openStream()) {
      String content = new String(ByteStreams.toByteArray(is), StandardCharsets.UTF_8);
      return readConfig(content);
    }
  }

  private static Configuration readConfig(String content) throws IOException {
    return Configuration.read(content);
  }

  @NullUnmarked
  static File getParentProjectPomFile(Model projectModel) {
    if (projectModel.getParent() == null) {
      return null;
    }

    File parentProjectPomFile =
        pomFile(projectModel.getProjectDirectory(), projectModel.getParent().getRelativePath());
    if (parentProjectPomFile.exists()) {
      return parentProjectPomFile;
    }

    return null;
  }

  @NullUnmarked
  static Set<File> getProjectModules(Model projectModel) {
    final Set<File> modules = new HashSet<>();

    // modules section
    for (String module : projectModel.getModules()) {
      modules.add(pomFile(projectModel.getProjectDirectory(), module));
    }

    // profiles section
    for (Profile profile : projectModel.getProfiles()) {

      // modules section
      for (String module : profile.getModules()) {
        modules.add(pomFile(projectModel.getProjectDirectory(), module));
      }
    }

    return modules.stream().filter(File::exists).collect(toSet());
  }

  @NullUnmarked
  static void updateParentVersion(Element projectElement, Parent parent) {
    Element parentElement = projectElement.getChild("parent");
    if (parentElement != null) {
      Element parentVersionElement = parentElement.getChild("version");
      parentVersionElement.setText(parent.getVersion());
    }
  }

  @NullUnmarked
  static void updateVersion(Element projectElement, Model projectModel) {
    Element versionElement = projectElement.getChild("version");
    if (versionElement != null) {
      versionElement.setText(projectModel.getVersion());
    }
  }

  @NullUnmarked
  static void updateDependencyVersions(Element element, ModelBase model) {
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

  @NullUnmarked
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

  @NullUnmarked
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

  @NullUnmarked
  static void updatePluginVersions(Element projectElement, BuildBase build, Reporting reporting) {
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

  @NullUnmarked
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

  @NullUnmarked
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

  @NullUnmarked
  private static String getPluginKey(Element element) {
    Element groupId = element.getChild("groupId");
    Element artifactId = element.getChild("artifactId");
    return (groupId != null ? groupId.getText().trim() : "org.apache.maven.plugins")
        + ":"
        + (artifactId != null ? artifactId.getText().trim() : "");
  }

  @NullUnmarked
  static String extensionLogHeader(Coords extensionCoords) {
    String extension = extensionCoords.toString();
    String metaInfo = "[core extension]";

    String plainLog = extension + " " + metaInfo;
    String formattedLog = buffer().a(" ").mojo(extension).a(" ").strong(metaInfo).a(" ").build();

    return padLogHeaderPadding(plainLog, formattedLog);
  }

  @NullUnmarked
  private static String padLogHeaderPadding(String plainLog, String formattedLog) {
    String pad = "-";
    int padding = max(6, 72 - 2 - plainLog.length());
    int paddingLeft = (int) floor(padding / 2.0);
    int paddingRight = (int) ceil(padding / 2.0);
    return buffer()
        .strong(repeat(pad, paddingLeft))
        .a(formattedLog)
        .strong(repeat(pad, paddingRight))
        .build();
  }

  @NullUnmarked
  static String projectLogHeader(Coords projectCoords) {
    String project = projectCoords.getProjectId();
    return buffer().project(project).build();
  }

  @NullUnmarked
  static String sectionLogHeader(String title, ModelBase model) {
    String header = title + ":";
    if (model instanceof Profile) {
      header = buffer().strong("profile " + ((Profile) model).getId() + " ") + header;
    }
    return header;
  }

  @NullUnmarked
  public static <T1, T2> void forEachPair(
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

  @NullUnmarked
  static String slugify(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("/", "-");
  }
}
