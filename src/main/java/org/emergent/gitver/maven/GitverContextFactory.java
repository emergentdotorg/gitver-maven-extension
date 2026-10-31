package org.emergent.gitver.maven;

import static org.apache.maven.shared.utils.StringUtils.rightPad;
import static org.apache.maven.shared.utils.logging.MessageUtils.buffer;

import java.io.File;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.model.Model;
import org.emergent.gitver.git.GitSituation;
import org.emergent.gitver.git.GitSituationFactory;
import org.emergent.gitver.maven.Configuration.RefConfig;
import org.emergent.gitver.util.GitverException;

/**
 * Wires the collaborators together: reads configuration, gathers the git situation, picks the
 * matching ref and prepares the version formatter. Returns empty when versioning is not applicable.
 */
@Slf4j
final class GitverContextFactory {

  private final GitverSession session;
  private final CommandOptions options;

  GitverContextFactory(GitverSession session) {
    this.session = session;
    this.options = new CommandOptions(session);
  }

  Optional<GitverContext> create(Model projectModel, File pomFile) {
    log.debug("pom file: {}", pomFile);
    File mvnDirectory = findMvnDirectory(pomFile);
    log.debug(".mvn directory: {}", mvnDirectory);

    Configuration config = readConfig(mvnDirectory);

    if (isDisabled(config)) {
      return Optional.empty();
    }

    GitSituation repositorySituation = new GitSituationFactory(options).create(pomFile);
    if (repositorySituation == null) {
      log.warn("skip - project is not part of a git repository");
      return Optional.empty();
    }
    logSituation(repositorySituation);

    GitVersionDetails versionDetails =
        GitVersionDetailsResolver.resolve(repositorySituation, config).orElse(null);
    if (versionDetails == null) {
      logNoMatchingRef(repositorySituation, config);
      return Optional.empty();
    }

    RefConfig refConfig = versionDetails.patchOptions();
    logRef(versionDetails);
    GitSituation gitSituation =
        repositorySituation.withDescribeOptions(
            Pattern.compile(refConfig.getTagPattern()), refConfig.isFollowFirstParentOnly());
    refConfig.getVersion().ifPresent(version -> log.info("  version: {}", version));
    if (!refConfig.getProperties().isEmpty()) {
      log.info("  properties: ");
      refConfig.getProperties().forEach((key, value) -> log.info("    {} - {}", key, value));
    }

    VersionFormatter formatter =
        new VersionFormatter(gitSituation, versionDetails, session, config);

    publishUserProperties(refConfig, formatter, projectModel);

    boolean updateSourcePom = resolveUpdateSourcePom(refConfig);
    if (updateSourcePom) {
      log.info("  updateSourcePom: {}", updateSourcePom);
    }
    boolean updateAttachedPom = resolveUpdateAttachedPom(refConfig);
    if (updateAttachedPom) {
      log.info("  updateAttachedPom: {}", updateAttachedPom);
    }

    RelatedProjects relatedProjects =
        RelatedProjects.determine(
            projectModel, config, mvnDirectory, gitSituation.getRootDirectory());
    if (log.isDebugEnabled()) {
      log.debug(buffer().strong("related projects:").build());
      relatedProjects.coords().forEach(gav -> log.debug("  {}", gav));
    }

    log.info("");
    ModelUpdater modelUpdater =
        new ModelUpdater(formatter, relatedProjects, gitSituation, versionDetails, session);
    return Optional.of(
        new GitverContext(relatedProjects, modelUpdater, updateSourcePom, updateAttachedPom));
  }

  private Configuration readConfig(File mvnDirectory) {
    String configFileName = options.get(CommandOptions.CONFIG_FILE_NAME);
    if (configFileName == null) {
      configFileName = Constants.CONFIG_FILE_NAME;
    }
    File configFile = new File(mvnDirectory, configFileName);
    log.debug("read config from {}", configFile);
    return Configuration.readOrDefault(configFile.toPath());
  }

  /** Command option takes precedence over the config option. */
  private boolean isDisabled(Configuration config) {
    Optional<Boolean> byOption = options.getBoolean(CommandOptions.DISABLE);
    if (byOption.isPresent()) {
      if (byOption.get()) {
        log.info("skip - versioning is disabled by command option");
      }
      return byOption.get();
    }
    if (config.isDisable()) {
      log.info("skip - versioning is disabled by config option");
      return true;
    }
    return false;
  }

  /** User properties of the matching ref are made available to the whole maven session. */
  private void publishUserProperties(
      RefConfig refConfig, VersionFormatter formatter, Model projectModel) {
    if (refConfig.getUserProperties().isEmpty()) {
      return;
    }
    log.info("  userProperties: ");
    String projectVersion = Coords.of(projectModel).getVersion();
    refConfig
        .getUserProperties()
        .forEach(
            (key, value) -> {
              log.info("    {} - {}", key, value);
              session
                  .getUserProperties()
                  .put(key, formatter.formatProperty(value, "", projectVersion));
            });
  }

  private boolean resolveUpdateAttachedPom(RefConfig refConfig) {
    return options
        .getBoolean(CommandOptions.UPDATE_ATTACHED_POM)
        .orElse(refConfig.isUpdateAttachedPom());
  }

  private boolean resolveUpdateSourcePom(RefConfig refConfig) {
    return options
        .getBoolean(CommandOptions.UPDATE_SOURCE_POM)
        .orElse(refConfig.isUpdateSourcePom());
  }

  private static File findMvnDirectory(File pomFile) {
    File searchDirectory = pomFile.getParentFile();
    while (searchDirectory != null) {
      File mvnDir = new File(searchDirectory, ".mvn");
      if (mvnDir.exists()) {
        return mvnDir;
      }
      searchDirectory = searchDirectory.getParentFile();
    }

    throw new GitverException("Can not find .mvn directory in hierarchy of " + pomFile);
  }

  private static void logSituation(GitSituation gitSituation) {
    if (log.isDebugEnabled()) {
      log.debug("git situation:");
      log.debug("  root directory: {}", gitSituation.getRootDirectory());
      log.debug("  head commit: {}", gitSituation.getRev());
      log.debug("  head commit timestamp: {}", gitSituation.getTimestamp());
      log.debug("  head branch: {}", gitSituation.getBranch());
      log.debug("  head tags: {}", gitSituation.getTags());
      log.debug("  head description: {}", gitSituation.getDescription());
    }
  }

  private static void logNoMatchingRef(GitSituation gitSituation, Configuration config) {
    log.warn("skip - no matching <ref> configuration defined");
    log.warn("git refs:");
    log.warn("  branch: {}", gitSituation.getBranch());
    log.warn("  tags: {}", gitSituation.getTags());
    log.warn("defined ref configurations:");
    config
        .getRefs()
        .forEach(
            ref ->
                log.warn(
                    "  {} - pattern: {}",
                    rightPad(ref.getType().name(), 6),
                    ref.getPattern().orElse(null)));
  }

  private static void logRef(GitVersionDetails details) {
    log.info("matching ref: {} - {}", details.refType().name(), details.refName());
    log.info(
        "ref configuration: {} - pattern: {}",
        details.refType().name(),
        details.patchOptions().getPattern().orElse(null));
  }
}
