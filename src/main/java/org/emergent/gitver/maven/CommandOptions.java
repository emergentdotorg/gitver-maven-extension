package org.emergent.gitver.maven;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Resolves extension options from, in order: maven user properties, environment variables and JVM
 * system properties.
 */
final class CommandOptions {

  static final String GIT_REF = "git.ref";
  static final String GIT_TAG = "git.tag";
  static final String GIT_BRANCH = "git.branch";
  static final String DISABLE = "versioning.disable";
  static final String UPDATE_SOURCE_POM = "versioning.updateSourcePom";
  static final String UPDATE_ATTACHED_POM = "versioning.updateAttachedPom";
  static final String CONFIG_FILE_NAME = "versioning.configFile";

  private final GitverSession session;

  CommandOptions(GitverSession session) {
    this.session = session;
  }

  @Nullable String get(String name) {
    String value = session.getUserProperties().getProperty(name);
    if (value == null) {
      String plainName = name.replaceFirst("^versioning\\.", "");
      String environmentVariableName =
          "VERSIONING_"
              + String.join("_", plainName.split("(?=\\p{Lu})"))
                  .replaceAll("\\.", "_")
                  .toUpperCase();
      value = System.getenv(environmentVariableName);
    }
    if (value == null) {
      value = System.getProperty(name);
    }
    return value;
  }

  Optional<Boolean> getBoolean(String name) {
    return Optional.ofNullable(get(name)).map(Boolean::parseBoolean);
  }
}
