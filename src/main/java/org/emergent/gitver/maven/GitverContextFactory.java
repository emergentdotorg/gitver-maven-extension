package org.emergent.gitver.maven;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static org.apache.maven.shared.utils.StringUtils.rightPad;
import static org.apache.maven.shared.utils.logging.MessageUtils.buffer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.model.Model;
import org.emergent.gitver.maven.Configuration.RefPatchDescription;
import org.jspecify.annotations.NullUnmarked;

/**
 * Wires the collaborators together: reads configuration, gathers the git situation, picks the
 * matching ref and prepares the version formatter. Returns empty when versioning is not applicable.
 */
@Slf4j
@NullUnmarked
final class GitverContextFactory {

  private final GitverSession session;
  private final CommandOptions options;

  GitverContextFactory(GitverSession session) {
    this.session = session;
    this.options = new CommandOptions(session);
  }

  Optional<GitverContext> create(Model projectModel, File pomFile) throws IOException {
    log.debug("pom file: {}", pomFile);
    File mvnDirectory = findMvnDirectory(pomFile);
    log.debug(".mvn directory: {}", mvnDirectory);

    Configuration config = readConfig(mvnDirectory);

    if (isDisabled(config)) {
      return Optional.empty();
    }

    GitSituation gitSituation = new GitSituationFactory(options).create(pomFile);
    if (gitSituation == null) {
      log.warn("skip - project is not part of a git repository");
      return Optional.empty();
    }
    logSituation(gitSituation);

    GitVersionDetails gitVersionDetails = GitVersionDetailsResolver.resolve(gitSituation, config);
    if (gitVersionDetails == null) {
      logNoMatchingRef(gitSituation, config);
      return Optional.empty();
    }

    final RefPatchDescription patchDescription = gitVersionDetails.patchDescription();
    logRef(gitVersionDetails);
    if (patchDescription.tagPattern != null && !patchDescription.tagPattern.equals(".*")) {
      log.info("  tagPattern: {}", patchDescription.tagPattern);
      gitSituation.setTagPattern(Pattern.compile(patchDescription.tagPattern));
    }
    if (patchDescription.followFirstParentOnly != null) {
      log.info("  followFirstParentOnly: {}", patchDescription.followFirstParentOnly);
      gitSituation.setFirstParent(patchDescription.followFirstParentOnly);
    }
    if (patchDescription.version != null) {
      log.info("  version: {}", patchDescription.version);
    }
    if (!patchDescription.properties.isEmpty()) {
      log.info("  properties: ");
      patchDescription.properties.forEach((key, value) -> log.info("    {} - {}", key, value));
    }

    VersionFormatter formatter =
        new VersionFormatter(gitSituation, gitVersionDetails, session, config);

    publishUserProperties(patchDescription, formatter, projectModel);

    boolean updateSourcePom = resolveUpdateSourcePom(patchDescription, config);
    if (updateSourcePom) {
      log.info("  updateSourcePom: {}", updateSourcePom);
    }
    boolean updateAttachedPom = resolveUpdateAttachedPom(patchDescription, config);
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
    return Optional.of(
        new GitverContext(
            gitSituation,
            gitVersionDetails,
            relatedProjects,
            formatter,
            session,
            updateSourcePom,
            updateAttachedPom));
  }

  private Configuration readConfig(File mvnDirectory) throws IOException {
    String configFileName = options.get(CommandOptions.CONFIG_FILE_NAME);
    if (configFileName == null) {
      configFileName = Constants.CONFIG_FILE_NAME;
    }
    final File configFile = new File(mvnDirectory, configFileName);
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
    if (config.getDisable()) {
      log.info("skip - versioning is disabled by config option");
      return true;
    }
    return false;
  }

  /** User properties of the matching ref are made available to the whole maven session. */
  private void publishUserProperties(
      RefPatchDescription patchDescription, VersionFormatter formatter, Model projectModel) {
    if (patchDescription.userProperties.isEmpty()) {
      return;
    }
    log.info("  userProperties: ");
    String projectVersion = Coords.of(projectModel).getVersion();
    patchDescription.userProperties.forEach(
        (key, value) -> {
          log.info("    {} - {}", key, value);
          session.getUserProperties().put(key, formatter.formatProperty(value, "", projectVersion));
        });
  }

  private boolean resolveUpdateSourcePom(PatchDescription refConfig, Configuration config) {
    return options
        .getBoolean(CommandOptions.UPDATE_SOURCE_POM)
        .or(
            () ->
                Stream.of(refConfig.updateSourcePom, config.getUpdateSourcePom())
                    .filter(Objects::nonNull)
                    .findFirst()
                    .map(TRUE::equals))
        .orElse(FALSE);
  }

  private boolean resolveUpdateAttachedPom(PatchDescription refConfig, Configuration config) {
    return options
        .getBoolean(CommandOptions.UPDATE_ATTACHED_POM)
        .or(
            () ->
                Stream.of(refConfig.updateAttachedPom, config.getUpdateAttachedPom())
                    .filter(Objects::nonNull)
                    .findFirst()
                    .map(TRUE::equals))
        .orElse(TRUE);
  }

  private static File findMvnDirectory(File pomFile) throws FileNotFoundException {
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
    log.warn("skip - no matching <ref> configuration and no <rev> configuration defined");
    log.warn("git refs:");
    log.warn("  branch: {}", gitSituation.getBranch());
    log.warn("  tags: {}", gitSituation.getTags());
    log.warn("defined ref configurations:");
    config
        .getRefs()
        .forEach(ref -> log.warn("  {} - pattern: {}", rightPad(ref.type.name(), 6), ref.pattern));
  }

  private static void logRef(GitVersionDetails details) {
    log.info("matching ref: {} - {}", details.refType().name(), details.refName());
    log.info(
        "ref configuration: {} - pattern: {}",
        details.refType().name(),
        details.patchDescription().pattern);
  }
}
