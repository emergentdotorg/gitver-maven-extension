package org.emergent.gittle.maven.extension;

import static org.apache.maven.shared.utils.logging.MessageUtils.buffer;

import com.pivovarit.function.exception.WrappedException;
import fr.brouillard.oss.jgitver.BranchingPolicy;
import fr.brouillard.oss.jgitver.GitVersionCalculator;
import fr.brouillard.oss.jgitver.Strategies;
import fr.brouillard.oss.jgitver.Version;
import fr.brouillard.oss.jgitver.metadata.Metadatas;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.emergent.gittle.core.CollectorsEx;
import org.emergent.gittle.core.GAV;
import org.emergent.gittle.core.GittleException;
import org.emergent.gittle.core.Util;
import org.emergent.gittle.maven.extension.LoggerEx.SilentImpl;
import org.emergent.gittle.maven.util.ConfigInfo;
import org.emergent.gittle.maven.util.Configuration;

@Slf4j
public class ExtensionUtil {

  public static final String EXTENSION_PREFIX = "gittle";
  public static final String EXTENSION_GROUP_ID = "org.emergent.maven.plugins";
  public static final String EXTENSION_ARTIFACT_ID = "gittle-maven-plugin";

  public static final String EXTENSION_EXPORT_PROPERTIES_PATH =
      EXTENSION_PREFIX + ".export-properties-path";

  public static final String REVISION = "revision";
  public static final String $_REVISION = "${revision}";

  static Model readModelFromPom(Path pomPath) {
    try (InputStream inputStream = Files.newInputStream(pomPath)) {
      MavenXpp3Reader reader = new MavenXpp3Reader();
      return reader.read(inputStream);
    } catch (IOException | XmlPullParserException e) {
      throw new GittleException(e.getMessage(), e);
    }
  }

  static Model readModelFromString(String xmlStr) {
    try (Reader reader = new StringReader(xmlStr)) {
      return new MavenXpp3Reader().read(reader);
    } catch (IOException | XmlPullParserException e) {
      throw new GittleException(e.getMessage(), e);
    }
  }

  static void writeModelToPom(Model projectModel, Path newPomPath) {
    try (Writer writer = Files.newBufferedWriter(newPomPath, Charset.defaultCharset())) {
      new MavenXpp3Writer().write(writer, projectModel);
    } catch (IOException e) {
      throw new GittleException(e.getMessage(), e);
    }
  }

  static String writeModelToString(Model projectModel) {
    try (Writer writer = new StringWriter()) {
      new MavenXpp3Writer().write(writer, projectModel);
      return writer.toString();
    } catch (IOException e) {
      throw new GittleException(e.getMessage(), e);
    }
  }

  // public static Xpp3Dom toXml(Config src) {
  //   JsonElement json = PropCodec.toJsonTree(src, Config.class);
  //   Map<String, Object> map = PropCodec.fromJsonTree(json, OBJ_MAP_TT.getType());
  //   return toXml("configuration", map);
  // }

  private static Xpp3Dom toXml(String name, Object value) {
    Xpp3Dom dom = new Xpp3Dom(name);
    if (value instanceof Map<?, ?> m) {
      toXml(m).forEach(dom::addChild);
    } else if (value != null) {
      dom.setValue(String.valueOf(value));
    }
    return dom;
  }

  private static List<Xpp3Dom> toXml(Map<?, ?> map) {
    return map.entrySet().stream()
        .filter(e -> e.getKey() instanceof String)
        .filter(e -> Objects.nonNull(e.getValue()))
        .map(e -> toXml((String) e.getKey(), e.getValue()))
        .toList();
  }

  public static boolean copyVersions(Model src, Model tgt) {
    String versionString =
        Optional.ofNullable(src.getProperties().getProperty(REVISION))
            .filter(v -> !$_REVISION.equals(v))
            .orElse(null);
    if (versionString == null
        && Optional.ofNullable(tgt.getVersion()).filter($_REVISION::equals).isPresent()) {
      versionString = src.getVersion();
    }
    if (versionString == null
        && Optional.ofNullable(tgt.getParent())
            .map(Parent::getVersion)
            .filter($_REVISION::equals)
            .isPresent()) {
      versionString = Optional.ofNullable(src.getParent()).map(Parent::getVersion).orElse(null);
    }
    return versionString != null && replaceRevision(tgt, versionString);
  }

  public static boolean replaceRevision(Model model, String versionString) {
    Build build =
        Optional.ofNullable(model.getBuild())
            .orElseGet(
                () -> {
                  model.setBuild(new Build());
                  return model.getBuild();
                });
    PluginManagement pluginMgmt =
        Optional.ofNullable(build.getPluginManagement())
            .orElseGet(
                () -> {
                  build.setPluginManagement(new PluginManagement());
                  return build.getPluginManagement();
                });
    DependencyManagement depsMgmt =
        Optional.ofNullable(model.getDependencyManagement())
            .orElseGet(
                () -> {
                  model.setDependencyManagement(new DependencyManagement());
                  return model.getDependencyManagement();
                });
    Properties properties = model.getProperties();

    AtomicReference<Boolean> modified = new AtomicReference<>(false);

    Supplier<String> ver =
        () -> {
          modified.set(true);
          return versionString;
        };

    if (Optional.ofNullable(model.getVersion()).filter($_REVISION::equals).isPresent()) {
      model.setVersion(ver.get());
    }

    if (Optional.ofNullable(model.getParent())
        .map(Parent::getVersion)
        .filter($_REVISION::equals)
        .isPresent()) {
      Parent parent = model.getParent();
      log.info("Setting parent {} version to {}", parent, versionString);
      model.getParent().setVersion(ver.get());
    }

    properties.stringPropertyNames().stream()
        .map(k -> Map.entry(k, properties.getProperty(k)))
        .filter(e -> Util.contains(e.getValue(), $_REVISION))
        .forEach(
            e -> {
              String replacement = Strings.CS.replace(e.getValue(), $_REVISION, ver.get());
              properties.setProperty(e.getKey(), replacement);
            });

    Stream.concat(build.getPlugins().stream(), pluginMgmt.getPlugins().stream())
        .filter(p -> $_REVISION.equals(p.getVersion()))
        .forEach(p -> p.setVersion(ver.get()));

    Stream.concat(
            Stream.concat(model.getDependencies().stream(), depsMgmt.getDependencies().stream()),
            Stream.concat(build.getPlugins().stream(), pluginMgmt.getPlugins().stream())
                .flatMap(plugin -> plugin.getDependencies().stream()))
        .filter(d -> $_REVISION.equals(d.getVersion()))
        .forEach(d -> d.setVersion(ver.get()));

    if (properties.containsKey(REVISION)) {
      properties.setProperty(REVISION, ver.get());
    }

    return modified.get();
  }

  public static void addBuildPlugin(Model projectModel, GAV rootProjectGav) {
    GAV gav = Util.getPluginGav();
    log.debug("Adding build plugin version {}", gav);

    Build build =
        Optional.ofNullable(projectModel.getBuild())
            .orElseGet(
                () -> {
                  projectModel.setBuild(new Build());
                  return projectModel.getBuild();
                });
    PluginManagement pluginMgmt =
        Optional.ofNullable(build.getPluginManagement())
            .orElseGet(
                () -> {
                  build.setPluginManagement(new PluginManagement());
                  return build.getPluginManagement();
                });

    String key = gav.getKey();

    Plugin normPlugin = build.getPluginsAsMap().get(key);
    Plugin mgmtPlugin = pluginMgmt.getPluginsAsMap().get(key);
    Optional<Plugin> found = Stream.of(normPlugin, mgmtPlugin).filter(Objects::nonNull).findFirst();

    found.ifPresent(
        existing ->
            log.warn(
                buffer()
                    .mojo(existing)
                    .warning(" version is different than ")
                    .mojo(gav)
                    .newline()
                    .a("This can introduce unexpected behaviors.")
                    .build()));

    if (found.isEmpty()) {
      // if (GAV.equalsIgnoreVersion(gav, rootProjectGav)) {
      Plugin plugin = createPlugin(gav);
      // addPluginConfiguration(
      //     plugin, Map.of(
      //         "updatePomFile", true
      //     )
      // );
      PluginExecution execution = new PluginExecution();
      execution.setId("flatten");
      execution.setGoals(List.of("flatten"));
      execution.setPhase("process-resources");
      plugin.addExecution(execution);
      // pluginMgmt.getPlugins().add(plugin);
      // build.getPlugins().add(createPlugin(gav.sansVersion()));
      build.getPlugins().add(0, plugin);
      // }
    }
  }

  private static Plugin createPlugin(GAV gav) {
    Plugin plugin = new Plugin();
    plugin.setGroupId(gav.getGroupId());
    plugin.setArtifactId(gav.getArtifactId());
    plugin.setVersion(gav.getVersion());
    return plugin;
  }

  public static void addPluginConfiguration(Plugin plugin, Map<String, ?> map) {
    Xpp3Dom configuration = new Xpp3Dom("configuration");
    map.forEach(
        (k, v) -> {
          if (!Objects.isNull(v)) {
            Xpp3Dom entry = new Xpp3Dom(k);
            entry.setValue(String.valueOf(v));
            configuration.addChild(entry);
          }
        });
    if (configuration.getChildCount() != 0 || Util.isNotEmpty(configuration.getValue())) {
      plugin.setConfiguration(configuration);
    }
  }

  // public static void addPluginConfiguration(Plugin plugin, Config config) {
  //   Xpp3Dom dom = toXml(config);
  //   if (dom.getChildCount() != 0 || Util.isNotEmpty(dom.getValue())) {
  //     plugin.setConfiguration(dom);
  //   }
  // }

  public static Map<String, String> getPropertiesFromMetadatas(InfoProvider info) {
    Map<String, String> map = getPropertiesFromMetadatas(info, "gittle.");
    Optional.ofNullable(map.get("gittle.version")).ifPresent(v -> map.putIfAbsent("revision", v));
    return map;
  }

  public static Map<String, String> getPropertiesFromMetadatas(InfoProvider info, String prefix) {
    String pfx = normalizePrefix(prefix);
    Map<String, String> map =
        Arrays.stream(Metadatas.values())
            .collect(
                CollectorsEx.toTreeMap(
                    m -> pfx + m.name().toLowerCase(Locale.ENGLISH),
                    m -> info.metadata().getOrDefault(m, "")));
    String ver = info.version().toString();
    map.put(pfx + "version", ver);
    map.put(pfx + "used_version", ver);
    map.put(pfx + "plugin-version", GittlePluginProperties.getVersion());
    return map;
  }

  private static String normalizePrefix(String prefix) {
    return prefix;
  }

  public static GAV toGav(MavenProject model) {
    Optional<MavenProject> parent = Optional.ofNullable(model.getParent());
    String groupId =
        Optional.ofNullable(model.getGroupId())
            .or(() -> parent.map(MavenProject::getGroupId))
            .orElse(null);
    String version =
        Optional.ofNullable(model.getVersion())
            .or(() -> parent.map(MavenProject::getVersion))
            .orElse(null);
    return new GAV(groupId, model.getArtifactId(), version);
  }

  /**
   * Builds a GAV object from the given Model object.
   *
   * @param model the project model to extract info from
   * @return a new GAV object
   */
  public static GAV toGav(Model model) {
    String groupId =
        (model.getGroupId() != null)
            ? model.getGroupId()
            : (model.getParent() != null ? model.getParent().getGroupId() : null);
    String version =
        (model.getVersion() != null)
            ? model.getVersion()
            : (model.getParent() != null ? model.getParent().getVersion() : null);

    return new GAV(groupId, model.getArtifactId(), version);
  }

  /**
   * Builds a GAV object from the given Parent object.
   *
   * @param parent the parent to extract info from
   * @return a new GAV object
   */
  public static GAV toGav(Parent parent) {
    return new GAV(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
  }

  public static boolean shouldSkip(MavenSession session) {
    return shouldSkip(session.getUserProperties(), session.getSystemProperties());
  }

  public static boolean shouldSkip(Map<?, ?>... props) {
    return getShould(EXTENSION_PREFIX + ".skip");
  }

  public static boolean shouldForceComputation(MavenSession session) {
    return shouldForceComputation(session.getUserProperties(), session.getSystemProperties());
  }

  public static boolean shouldForceComputation(Map<?, ?>... props) {
    return getShould(EXTENSION_PREFIX + ".forceComputation", props);
  }

  private static Boolean getShould(String propKey, Map<?, ?>... props) {
    return Arrays.stream(props)
        .map(p -> p.get(propKey))
        .filter(CharSequence.class::isInstance)
        .map(String::valueOf)
        .findFirst()
        .map(Boolean::parseBoolean)
        .orElse(false);
  }

  public static Optional<String> versionOverride(MavenSession session) {
    return versionOverride(session.getUserProperties(), session.getSystemProperties());
  }

  public static Optional<String> versionOverride(
      Properties userProperties, Properties systemProperties) {
    return getProperty(EXTENSION_PREFIX + ".use-version", userProperties, systemProperties);
  }

  public static Optional<String> exportPropertiesPath(MavenSession session) {
    return exportPropertiesPath(session.getUserProperties(), session.getSystemProperties());
  }

  public static Optional<String> exportPropertiesPath(
      Properties userProperties, Properties systemProperties) {
    return getProperty(EXTENSION_EXPORT_PROPERTIES_PATH, userProperties, systemProperties);
  }

  public static Optional<String> getProperty(MavenSession session, String propertyName) {
    return getProvider(session).getProperty(propertyName);
  }

  public static Optional<String> getProperty(
      String propertyName, Properties userProperties, Properties systemProperties) {
    return PropertiesProvider.builder()
        .setUserProperties(userProperties)
        .setSystemProperties(systemProperties)
        .build()
        .getProperty(propertyName);
  }

  /**
   * Tries to get the property from the user properties ({@link MavenSession#getUserProperties()})
   * or from the system properties ({@link MavenSession#getSystemProperties()}).
   *
   * <p>The variable can be defined with it's exact name or with it's IEEE Std 1003.1-2001 compliant
   * version ({@link #normalizeSystemPropertyName(String)}).
   *
   * <p>User properties have higher priority than all other properties. Environment properties have
   * higher priority than system properties. Exact matches have higher priority than IEEE Std
   * 1003.1-2001 compliant versions.
   *
   * @param session A running maven session.
   * @param propertyName The name of the property to retrieve.
   * @param logger logger.
   * @return The value of the property of empty if it has not been defined.
   */
  public static Optional<String> getProperty(
      MavenSession session, String propertyName, LoggerEx logger) {
    PropertiesProvider provider = getProvider(session);
    return provider.toBuilder().setLogger(logger).build().getProperty(propertyName);
  }

  private static PropertiesProvider getProvider(MavenSession session) {
    return PropertiesProvider.builder()
        .setUserProperties(session.getUserProperties())
        .setSystemProperties(session.getSystemProperties())
        .build();
  }

  public static Optional<String> getProperty(
      LoggerEx logger,
      String propertyName,
      Properties userProperties,
      Properties systemProperties) {
    return getUserProperty(logger, propertyName, userProperties)
        .or(() -> getSystemProperty(logger, propertyName, systemProperties));
  }

  public static Optional<String> getProperty(
      LoggerEx logger, String propertyName, PropertiesProvider provider) {
    return getUserProperty(logger, propertyName, provider.getUserProperties())
        .or(() -> getSystemProperty(logger, propertyName, provider.getSystemProperties()));
  }

  public static Optional<String> getUserProperty(
      LoggerEx logger, String propertyName, Map<?, ?> userProperties) {
    String normalizedName = normalizeSystemPropertyName(propertyName);
    Optional<Entry<String, String>> value =
        Stream.of(propertyName, normalizedName)
            .map(
                n ->
                    Optional.ofNullable(userProperties.get(n))
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .map(v -> Map.entry(n, v))
                        .orElse(null))
            .filter(Objects::nonNull)
            .findFirst();
    value.ifPresent(
        e -> logger.debug("Found '{}'='{}' in user properties", e.getKey(), e.getValue()));
    return value.map(Entry::getValue);
  }

  public static Optional<String> getSystemProperty(
      LoggerEx logger, String propertyName, Map<?, ?> systemProperties) {
    final String envPrefix = "env.";
    String normalizedName = normalizeSystemPropertyName(propertyName);
    Optional<Entry<String, String>> value =
        Stream.of(
                envPrefix + propertyName, envPrefix + normalizedName, propertyName, normalizedName)
            .map(
                n ->
                    Optional.ofNullable(systemProperties.get(n))
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .map(v -> Map.entry(n, v))
                        .orElse(null))
            .filter(Objects::nonNull)
            .findFirst();

    value.ifPresent(
        e -> {
          String pname = e.getKey();
          String propType = pname.startsWith(envPrefix) ? "env" : "system";
          logger.debug(
              "Found '{}'='{}' in system properties (as {} property)",
              pname,
              e.getValue(),
              propType);
        });

    return value.map(Entry::getValue);
  }

  /**
   * Derives an IEEE Std 1003.1-2001 compliant property name by replacing all non-compliant
   * characters with underscore.
   *
   * <p>In IEEE Std 1003.1-2001 it was defined that the variable name consist solely of uppercase
   * letters, digits, and the '_' (underscore) from the characters defined in Portable Character Set
   * and do not begin with a digit. Although IEEE Std 1003.1-2008 / IEEE POSIX P1003.2/ISO 9945.2
   * doesn't define a lexical convention for variable names, most bash implementations use
   * `[a-zA-Z_]+[a-zA-Z0-9_]*`.
   *
   * @return A derived IEEE Std 1003.1-2001 compliant property name.
   */
  public static String normalizeSystemPropertyName(String mavenPropertyName) {
    if (StringUtils.isBlank(mavenPropertyName)) {
      throw new IllegalStateException(
          "It's not possible to normalize a blank name into a compliant name");
    }

    CharSequence result = mavenPropertyName;
    result = RegExUtils.replacePattern(result, "[^a-zA-Z0-9_]", "_");
    result = RegExUtils.replacePattern(result, "^[0-9]", "_");
    return result.toString();
  }

  public static GittleSession createGittleSession(MavenSession mavenSession) {
    MavenExecutionRequest request = mavenSession.getRequest();
    // request.getProjectBuildingRequest().getProject();
    File rootDirectory = request.getMultiModuleProjectDirectory();
    log.debug("using " + EXTENSION_PREFIX + " on directory: {}", rootDirectory);
    ConfigInfo configInfo = ConfigInfo.load(rootDirectory);
    InfoProvider infoProvider = getInfoProvider(mavenSession, configInfo);
    return GittleSession.builder().setConfigInfo(configInfo).setInfoProvider(infoProvider).build();
  }

  private static InfoProvider getInfoProvider(MavenSession mavenSession, ConfigInfo configInfo) {
    File rootDirectory = mavenSession.getRequest().getMultiModuleProjectDirectory();
    try (GitVersionCalculator gitVersionCalculator = GitVersionCalculator.location(rootDirectory)) {
      Configuration cfg = configInfo.configuration();

      if (cfg.strategy != null) {
        gitVersionCalculator.setStrategy(cfg.strategy);
      } else {
        gitVersionCalculator.setStrategy(Strategies.CONFIGURABLE);
        // gitVersionCalculator.setMavenLike(cfg.mavenLike);
      }

      if (cfg.policy != null) {
        gitVersionCalculator.setLookupPolicy(cfg.policy);
      }

      gitVersionCalculator
          .setAutoIncrementPatch(cfg.autoIncrementPatch)
          .setUseDirty(cfg.useDirty)
          .setUseDistance(cfg.useCommitDistance)
          .setUseGitCommitTimestamp(cfg.useGitCommitTimestamp)
          .setUseGitCommitId(cfg.useGitCommitId)
          .setUseSnapshot(cfg.useSnapshot)
          .setGitCommitIdLength(cfg.gitCommitIdLength)
          .setUseDefaultBranchingPolicy(cfg.useDefaultBranchingPolicy)
          .setNonQualifierBranches(cfg.nonQualifierBranches)
          .setVersionPattern(cfg.versionPattern)
          .setTagVersionPattern(cfg.tagVersionPattern)
          .setScript(cfg.script)
          .setScriptType(cfg.scriptType);

      if (cfg.maxSearchDepth >= 1) {
        // keep redundant test in case we change UNSET_DEPTH value
        gitVersionCalculator.setMaxDepth(cfg.maxSearchDepth);
      }

      if (shouldForceComputation(mavenSession)) {
        gitVersionCalculator.setForceComputation(true);
      }

      if (cfg.regexVersionTag != null) {
        gitVersionCalculator.setFindTagVersionPattern(cfg.regexVersionTag);
      }

      if (cfg.branchPolicies != null && !cfg.branchPolicies.isEmpty()) {
        List<BranchingPolicy> policies =
            cfg.branchPolicies.stream()
                .map(bp -> new BranchingPolicy(bp.pattern, bp.transformations))
                .collect(Collectors.toList());

        gitVersionCalculator.setQualifierBranchingPolicies(policies);
      }

      log.info(
          "Using gittle-maven-plugin [{}] (sha1: {})",
          GittlePluginProperties.getVersion(),
          GittlePluginProperties.getSHA1());
      Instant start = Instant.now();

      String computedVersion = gitVersionCalculator.getVersion();

      Duration duration = Duration.between(start, Instant.now());
      log.info("    version '{}' computed in {} ms", computedVersion, duration.toMillis());
      log.info("");

      InfoProvider infoProvider = getInfoProvider(mavenSession, gitVersionCalculator);
      return infoProvider;
    } catch (Exception e) {
      throw new WrappedException(e);
    }
  }

  private static InfoProvider getInfoProvider(
      MavenSession mavenSession, GitVersionCalculator calculator) {
    Version version =
        versionOverride(mavenSession.getUserProperties(), mavenSession.getSystemProperties())
            .map(Version::parse)
            .orElseGet(calculator::getVersionObject);

    Map<Metadatas, String> meta =
        Arrays.stream(Metadatas.values())
            .map(m -> calculator.meta(m).map(v -> Map.entry(m, v)).orElse(null))
            .filter(Objects::nonNull)
            .collect(CollectorsEx.toTreeMap(Entry::getKey, Entry::getValue));

    return new InfoProvider(version, meta);
  }

  @Value
  @Builder(toBuilder = true, setterPrefix = "set", builderClassName = "Builder")
  public static class PropertiesProvider {

    private static final String envPrefix = "env.";

    @Default Map<?, ?> userProperties = new Properties();
    @Default Map<?, ?> systemProperties = System.getProperties();
    @Default LoggerEx logger = SilentImpl.INSTANCE;

    public Optional<String> getProperty(String propertyName) {
      return getUserProperty(propertyName).or(() -> getSystemProperty(propertyName));
    }

    private Optional<String> getUserProperty(String propertyName) {
      return ExtensionUtil.getUserProperty(logger, propertyName, userProperties);
    }

    private Optional<String> getSystemProperty(String propertyName) {
      return ExtensionUtil.getSystemProperty(logger, propertyName, userProperties);
    }
  }
}
