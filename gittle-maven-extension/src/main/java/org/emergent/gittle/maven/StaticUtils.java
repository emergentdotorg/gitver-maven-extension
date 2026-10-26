package org.emergent.gittle.maven;

import static com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS;
import static java.lang.Math.ceil;
import static java.lang.Math.floor;
import static java.lang.Math.max;
import static java.util.Objects.requireNonNullElse;
import static java.util.stream.Collectors.toSet;
import static org.apache.maven.shared.utils.StringUtils.leftPad;
import static org.apache.maven.shared.utils.StringUtils.repeat;
import static org.apache.maven.shared.utils.logging.MessageUtils.buffer;
import static org.emergent.gittle.commons.GitRefType.BRANCH;
import static org.emergent.gittle.commons.GitRefType.COMMIT;
import static org.emergent.gittle.commons.GitRefType.TAG;
import static org.emergent.gittle.commons.StringUtil.patternGroupValues;
import static org.emergent.gittle.commons.StringUtil.patternGroups;
import static org.emergent.gittle.maven.MavenUtil.pomFile;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import de.pdark.decentxml.Element;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.ModelBase;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.model.ReportPlugin;
import org.apache.maven.model.Reporting;
import org.emergent.gittle.commons.GitDescription;
import org.emergent.gittle.commons.GitSituation;
import org.emergent.gittle.commons.Lazy;
import org.emergent.gittle.maven.Configuration.PatchDescription;
import org.emergent.gittle.maven.Configuration.RefPatchDescription;

class StaticUtils {

  static final Pattern VERSION_PATTERN =
      Pattern.compile(
          ".*?(?<version>(?<core>(?<major>\\d+)(?:\\.(?<minor>\\d+)(?:\\.(?<patch>\\d+))?)?)(?:\\.(?<build>\\d+))?(?:-(?<label>.*))?)|");
  private static final Pattern LABEL_TRAILING_NUM = Pattern.compile("^(.*?)(\\d*)$");

  static GitVersionDetails getGitVersionDetails(GitSituation gitSituation, Configuration config) {
    final Lazy<List<String>> sortedTags = Lazy.by(gitSituation::getTags);
    for (RefPatchDescription refConfig : config.refs.list) {
      switch (refConfig.type) {
        case TAG:
          {
            if (gitSituation.isDetached() || config.refs.considerTagsOnBranches) {
              for (String tag : sortedTags.get()) {
                if (refConfig.pattern == null || refConfig.pattern().matcher(tag).matches()) {
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
              if (refConfig.pattern == null || refConfig.pattern().matcher(branch).matches()) {
                return new GitVersionDetails(gitSituation.getRev(), BRANCH, branch, refConfig);
              }
            }
          }
          break;
        default:
          throw new IllegalArgumentException("Unexpected ref type: " + refConfig.type);
      }
    }

    if (config.rev != null) {
      return new GitVersionDetails(
          gitSituation.getRev(),
          COMMIT,
          gitSituation.getRev(),
          new RefPatchDescription(COMMIT, null, config.rev));
    }

    return null;
  }

  static Map<String, Supplier<String>> generateFormatPlaceholderMap(
      Map<String, Supplier<String>> globalFormatPlaceholderMap,
      Configuration config,
      String projectVersion) {
    final Map<String, Supplier<String>> placeholderMap = new HashMap<>(globalFormatPlaceholderMap);

    placeholderMap.put("version", Lazy.of(projectVersion));

    final Lazy<Matcher> versionComponents = Lazy.by(() -> matchVersion(projectVersion));

    placeholderMap.put(
        "version.core",
        Lazy.by(() -> requireNonNullElse(versionComponents.get().group("core"), "0.0.0")));

    placeholderMap.put(
        "version.major",
        Lazy.by(() -> requireNonNullElse(versionComponents.get().group("major"), "0")));
    placeholderMap.put(
        "version.major.next",
        Lazy.by(() -> increase(placeholderMap.get("version.major").get(), 1)));

    placeholderMap.put(
        "version.minor",
        Lazy.by(() -> requireNonNullElse(versionComponents.get().group("minor"), "0")));
    placeholderMap.put(
        "version.minor.next",
        Lazy.by(() -> increase(placeholderMap.get("version.minor").get(), 1)));

    placeholderMap.put(
        "version.patch",
        Lazy.by(() -> requireNonNullElse(versionComponents.get().group("patch"), "0")));
    placeholderMap.put(
        "version.patch.next",
        Lazy.by(() -> increase(placeholderMap.get("version.patch").get(), 1)));

    placeholderMap.put(
        "version.build",
        Lazy.by(() -> requireNonNullElse(versionComponents.get().group("build"), "")));
    placeholderMap.put(
        "version.build.next",
        Lazy.by(() -> increase(placeholderMap.get("version.build").get(), 1)));

    placeholderMap.put(
        "version.label",
        Lazy.by(() -> requireNonNullElse(versionComponents.get().group("label"), "")));
    placeholderMap.put(
        "version.label.prefixed",
        Lazy.by(
            () -> {
              String label = placeholderMap.get("version.label").get();
              return !label.isEmpty() ? "-" + label : "";
            }));

    placeholderMap.put("version.next", Lazy.by(() -> nextVersion(versionComponents.get())));

    // deprecated
    placeholderMap.put("version.release", Lazy.by(() -> projectVersion.replaceFirst("-.*$", "")));

    final Pattern projectVersionPattern = config.projectVersionPattern();
    if (projectVersionPattern != null) {
      // ref pattern groups
      for (Entry<String, String> patternGroup :
          patternGroupValues(projectVersionPattern, projectVersion).entrySet()) {
        final String groupName = patternGroup.getKey();
        final String value = patternGroup.getValue() != null ? patternGroup.getValue() : "";

        final var placeholderKey = "version." + groupName;
        // ensure no placeholder overwrites
        if (placeholderMap.containsKey(placeholderKey)) {
          throw new IllegalArgumentException(
              "project version pattern capture group can not be named '"
                  + groupName
                  + "', because this would overwrite extension placeholder ${"
                  + placeholderKey
                  + "}");
        }
        placeholderMap.put(placeholderKey, () -> value);
      }
    }

    return placeholderMap;
  }

  static Map<String, Supplier<String>> generateGlobalFormatPlaceholderMap(
      GitSituation gitSituation, GitVersionDetails gitVersionDetails, MavenSession mavenSession) {

    final Map<String, Supplier<String>> placeholderMap = new HashMap<>();

    final Lazy<String> hash = Lazy.by(gitSituation::getRev);
    placeholderMap.put("commit", hash);
    placeholderMap.put("commit.short", Lazy.by(() -> hash.get().substring(0, 7)));

    final Lazy<ZonedDateTime> headCommitDateTime = Lazy.by(gitSituation::getTimestamp);
    placeholderMap.put(
        "commit.timestamp",
        Lazy.by(() -> String.valueOf(headCommitDateTime.get().toEpochSecond())));
    placeholderMap.put(
        "commit.timestamp.year", Lazy.by(() -> String.valueOf(headCommitDateTime.get().getYear())));
    placeholderMap.put(
        "commit.timestamp.year.2digit",
        Lazy.by(() -> String.valueOf(headCommitDateTime.get().getYear() % 100)));
    placeholderMap.put(
        "commit.timestamp.month",
        Lazy.by(() -> leftPad(String.valueOf(headCommitDateTime.get().getMonthValue()), 2, "0")));
    placeholderMap.put(
        "commit.timestamp.day",
        Lazy.by(() -> leftPad(String.valueOf(headCommitDateTime.get().getDayOfMonth()), 2, "0")));
    placeholderMap.put(
        "commit.timestamp.hour",
        Lazy.by(() -> leftPad(String.valueOf(headCommitDateTime.get().getHour()), 2, "0")));
    placeholderMap.put(
        "commit.timestamp.minute",
        Lazy.by(() -> leftPad(String.valueOf(headCommitDateTime.get().getMinute()), 2, "0")));
    placeholderMap.put(
        "commit.timestamp.second",
        Lazy.by(() -> leftPad(String.valueOf(headCommitDateTime.get().getSecond()), 2, "0")));
    placeholderMap.put(
        "commit.timestamp.datetime",
        Lazy.by(
            () ->
                headCommitDateTime.get().toEpochSecond() > 0
                    ? headCommitDateTime
                        .get()
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss"))
                    : "00000101.000000"));
    placeholderMap.put(
        "commit.timestamp.iso",
        Lazy.by(
            () ->
                headCommitDateTime.get().toEpochSecond() > 0
                    ? headCommitDateTime.get().format(DateTimeFormatter.ISO_INSTANT)
                    : "0000-01-01T00:00:00Z"));

    final Lazy<ZonedDateTime> buildCommitDateTime =
        Lazy.by(() -> mavenSession.getStartTime().toInstant().atZone(ZoneId.systemDefault()));
    placeholderMap.put(
        "build.timestamp",
        Lazy.by(() -> String.valueOf(buildCommitDateTime.get().toEpochSecond())));
    placeholderMap.put(
        "build.timestamp.year", Lazy.by(() -> String.valueOf(buildCommitDateTime.get().getYear())));
    placeholderMap.put(
        "build.timestamp.year.2digit",
        Lazy.by(() -> String.valueOf(buildCommitDateTime.get().getYear() % 100)));
    placeholderMap.put(
        "build.timestamp.month",
        Lazy.by(() -> leftPad(String.valueOf(buildCommitDateTime.get().getMonthValue()), 2, "0")));
    placeholderMap.put(
        "build.timestamp.day",
        Lazy.by(() -> leftPad(String.valueOf(buildCommitDateTime.get().getDayOfMonth()), 2, "0")));
    placeholderMap.put(
        "build.timestamp.hour",
        Lazy.by(() -> leftPad(String.valueOf(buildCommitDateTime.get().getHour()), 2, "0")));
    placeholderMap.put(
        "build.timestamp.minute",
        Lazy.by(() -> leftPad(String.valueOf(buildCommitDateTime.get().getMinute()), 2, "0")));
    placeholderMap.put(
        "build.timestamp.second",
        Lazy.by(() -> leftPad(String.valueOf(buildCommitDateTime.get().getSecond()), 2, "0")));
    placeholderMap.put(
        "build.timestamp.datetime",
        Lazy.by(
            () ->
                buildCommitDateTime.get().toEpochSecond() > 0
                    ? buildCommitDateTime
                        .get()
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss"))
                    : "00000000.000000"));
    placeholderMap.put(
        "build.timestamp.iso",
        Lazy.by(
            () ->
                buildCommitDateTime.get().toEpochSecond() > 0
                    ? headCommitDateTime.get().format(DateTimeFormatter.ISO_INSTANT)
                    : "0000-01-01T00:00:00Z"));

    final String refName = gitVersionDetails.getRefName();
    final Lazy<String> refNameSlug = Lazy.by(() -> slugify(refName));
    placeholderMap.put("ref", () -> refName);
    placeholderMap.put("ref" + ".slug", refNameSlug);

    final Pattern refPattern = gitVersionDetails.getPatchDescription().pattern();
    if (refPattern != null) {
      // ref pattern groups
      for (Entry<String, String> patternGroup :
          patternGroupValues(refPattern, refName).entrySet()) {
        final String groupName = patternGroup.getKey();
        final String value = patternGroup.getValue() != null ? patternGroup.getValue() : "";

        final var placeholderKey = "ref." + groupName;
        // ensure no placeholder overwrites
        if (placeholderMap.containsKey(placeholderKey)) {
          throw new IllegalArgumentException(
              "ref pattern capture group can not be named '"
                  + groupName
                  + "', because this would overwrite extension placeholder ${"
                  + placeholderKey
                  + "}");
        }
        placeholderMap.put(placeholderKey, () -> value);
        placeholderMap.put(placeholderKey + ".slug", Lazy.by(() -> slugify(value)));
      }
    }

    // dirty
    final Lazy<Boolean> dirty = Lazy.by(() -> !gitSituation.isClean());
    placeholderMap.put("dirty", Lazy.by(() -> dirty.get() ? "-DIRTY" : ""));
    placeholderMap.put("dirty.snapshot", Lazy.by(() -> dirty.get() ? "-SNAPSHOT" : ""));

    // describe
    final Lazy<GitDescription> description = Lazy.by(gitSituation::getDescription);
    placeholderMap.put("describe", Lazy.by(() -> description.get().toString()));
    final Lazy<String> descriptionTag = Lazy.by(() -> description.get().getTag());
    placeholderMap.put("describe.tag", descriptionTag);

    final Lazy<Matcher> descriptionTagVersionMatcher =
        Lazy.by(() -> matchVersion(descriptionTag.get()));

    placeholderMap.put(
        "describe.tag.version",
        Lazy.by(
            () ->
                requireNonNullElse(descriptionTagVersionMatcher.get().group("version"), "0.0.0")));

    placeholderMap.put(
        "describe.tag.version.core",
        Lazy.by(() -> requireNonNullElse(descriptionTagVersionMatcher.get().group("core"), "0")));

    placeholderMap.put(
        "describe.tag.version.major",
        Lazy.by(() -> requireNonNullElse(descriptionTagVersionMatcher.get().group("major"), "0")));
    placeholderMap.put(
        "describe.tag.version.major.next",
        Lazy.by(() -> increase(placeholderMap.get("describe.tag.version.major").get(), 1)));

    placeholderMap.put(
        "describe.tag.version.minor",
        Lazy.by(() -> requireNonNullElse(descriptionTagVersionMatcher.get().group("minor"), "0")));
    placeholderMap.put(
        "describe.tag.version.minor.next",
        Lazy.by(() -> increase(placeholderMap.get("describe.tag.version.minor").get(), 1)));

    placeholderMap.put(
        "describe.tag.version.patch",
        Lazy.by(() -> requireNonNullElse(descriptionTagVersionMatcher.get().group("patch"), "0")));
    placeholderMap.put(
        "describe.tag.version.patch.next",
        Lazy.by(() -> increase(placeholderMap.get("describe.tag.version.patch").get(), 1)));

    placeholderMap.put(
        "describe.tag.version.build",
        Lazy.by(() -> requireNonNullElse(descriptionTagVersionMatcher.get().group("build"), "")));
    placeholderMap.put(
        "describe.tag.version.build.next",
        Lazy.by(() -> increase(placeholderMap.get("describe.tag.version.build").get(), 1)));

    placeholderMap.put(
        "describe.tag.version.label",
        Lazy.by(() -> requireNonNullElse(descriptionTagVersionMatcher.get().group("label"), "")));
    placeholderMap.put(
        "describe.tag.version.label.next",
        Lazy.by(() -> increase(placeholderMap.get("describe.tag.version.label").get(), 1)));

    placeholderMap.put(
        "describe.tag.version.next",
        Lazy.by(() -> nextVersion(descriptionTagVersionMatcher.get())));

    final Lazy<Integer> descriptionDistance = Lazy.by(() -> description.get().getDistance());
    placeholderMap.put(
        "describe.distance", Lazy.by(() -> String.valueOf(descriptionDistance.get())));
    placeholderMap.put(
        "describe.distance.snapshot",
        Lazy.by(() -> (descriptionDistance.get() == 0 ? "" : "-SNAPSHOT")));

    placeholderMap.put(
        "describe.tag.version.patch.plus.describe.distance",
        Lazy.by(
            () ->
                increase(
                    placeholderMap.get("describe.tag.version.patch").get(),
                    descriptionDistance.get())));
    placeholderMap.put(
        "describe.tag.version.patch.next.plus.describe.distance",
        Lazy.by(
            () ->
                increase(
                    placeholderMap.get("describe.tag.version.patch.next").get(),
                    descriptionDistance.get())));

    placeholderMap.put(
        "describe.tag.version.build.plus.describe.distance",
        Lazy.by(
            () ->
                increase(
                    placeholderMap.get("describe.tag.version.build").get(),
                    descriptionDistance.get())));
    placeholderMap.put(
        "describe.tag.version.build.next.plus.describe.distance",
        Lazy.by(
            () ->
                increase(
                    placeholderMap.get("describe.tag.version.build.next").get(),
                    descriptionDistance.get())));

    placeholderMap.put(
        "describe.tag.version.label.plus.describe.distance",
        Lazy.by(
            () ->
                increase(
                    placeholderMap.get("describe.tag.version.label").get(),
                    descriptionDistance.get())));
    placeholderMap.put(
        "describe.tag.version.label.next.plus.describe.distance",
        Lazy.by(
            () ->
                increase(
                    placeholderMap.get("describe.tag.version.label.next").get(),
                    descriptionDistance.get())));

    // describe tag pattern groups
    final Lazy<Map<String, String>> describeTagPatternValues =
        Lazy.by(
            () -> patternGroupValues(gitSituation.getDescribeTagPattern(), descriptionTag.get()));
    for (String groupName : patternGroups(gitSituation.getDescribeTagPattern())) {
      final var placeholderKey = "describe.tag." + groupName;
      // ensure no placeholder overwrites
      if (placeholderMap.containsKey(placeholderKey)) {
        throw new IllegalArgumentException(
            "describe tag pattern capture group can not be named '"
                + groupName
                + "', because this would overwrite extension placeholder ${"
                + placeholderKey
                + "}");
      }
      Lazy<String> groupValue = Lazy.by(() -> describeTagPatternValues.get().get(groupName));
      placeholderMap.put(placeholderKey, groupValue);
      placeholderMap.put(placeholderKey + ".slug", Lazy.by(() -> slugify(groupValue.get())));
    }

    // command parameters e.g. mvn -Dfoo=123 will be available as ${property.foo}
    for (Entry<Object, Object> property : mavenSession.getUserProperties().entrySet()) {
      if (property.getValue() != null) {
        placeholderMap.put("property." + property.getKey(), () -> property.getValue().toString());
      }
    }

    // environment variables e.g. BUILD_NUMBER=123 will be available as ${env.BUILD_NUMBER}
    System.getenv().forEach((key, value) -> placeholderMap.put("env." + key, () -> value));

    return placeholderMap;
  }

  private static Matcher matchVersion(String input) {
    Matcher matcher = VERSION_PATTERN.matcher(input);
    //noinspection ResultOfMethodCallIgnored
    matcher.find();

    return matcher;
  }

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

  static Configuration readConfig(File configFile) throws IOException {
    final XmlMapper xmlMapper = XmlMapper.builder().enable(ACCEPT_CASE_INSENSITIVE_ENUMS).build();

    final Configuration config = xmlMapper.readValue(configFile, Configuration.class);

    // consider global config
    List<PatchDescription> patchDescriptions = new ArrayList<>(config.refs.list);
    if (config.rev != null) {
      patchDescriptions.add(config.rev);
    }
    for (PatchDescription patchDescription : patchDescriptions) {
      if (patchDescription.describeTagPattern == null) {
        patchDescription.describeTagPattern = config.describeTagPattern;
      }
      if (patchDescription.describeTagFirstParent == null) {
        patchDescription.describeTagFirstParent = config.describeTagFirstParent;
      }
      if (patchDescription.updatePom == null) {
        patchDescription.updatePom = config.updatePom;
      }
    }

    return config;
  }

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

  static void updateParentVersion(Element projectElement, Parent parent) {
    Element parentElement = projectElement.getChild("parent");
    if (parentElement != null) {
      Element parentVersionElement = parentElement.getChild("version");
      parentVersionElement.setText(parent.getVersion());
    }
  }

  static void updateVersion(Element projectElement, Model projectModel) {
    Element versionElement = projectElement.getChild("version");
    if (versionElement != null) {
      versionElement.setText(projectModel.getVersion());
    }
  }

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

  static String extensionLogHeader(GAV extensionGAV) {
    String extension = extensionGAV.toString();
    String metaInfo = "[core extension]";

    String plainLog = extension + " " + metaInfo;
    String formattedLog = buffer().a(" ").mojo(extension).a(" ").strong(metaInfo).a(" ").build();

    return padLogHeaderPadding(plainLog, formattedLog);
  }

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

  static String projectLogHeader(GAV projectGAV) {
    String project = projectGAV.getProjectId();
    return buffer().project(project).build();
  }

  static String sectionLogHeader(String title, ModelBase model) {
    String header = title + ":";
    if (model instanceof Profile) {
      header = buffer().strong("profile " + ((Profile) model).getId() + " ") + header;
    }
    return header;
  }

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

  static String slugify(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("/", "-");
  }

  private static String increase(String number, long increment) {
    String sanitized = number.isEmpty() ? "0" : number;
    return String.format(
        "%0" + sanitized.length() + "d",
        Long.parseLong(number.isEmpty() ? "0" : number) + increment);
  }

  static String nextVersion(Matcher m) {
    String label = m.group("label");
    if (label != null && !label.isEmpty()) {
      Matcher tail = LABEL_TRAILING_NUM.matcher(label);
      tail.matches();
      String coreAndBuild =
          m.group("core") + (m.group("build") != null ? "." + m.group("build") : "");
      return coreAndBuild + "-" + tail.group(1) + increase(tail.group(2), 1);
    }
    if (m.group("build") != null) {
      return m.group("major")
          + "."
          + m.group("minor")
          + "."
          + m.group("patch")
          + "."
          + increase(m.group("build"), 1);
    } else if (m.group("patch") != null) {
      return m.group("major") + "." + m.group("minor") + "." + increase(m.group("patch"), 1);
    } else if (m.group("minor") != null) {
      return m.group("major") + "." + increase(m.group("minor"), 1);
    } else {
      return increase(requireNonNullElse(m.group("major"), "0"), 1);
    }
  }
}
