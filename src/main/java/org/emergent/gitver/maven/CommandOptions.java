package org.emergent.gitver.maven;

import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Resolves extension options from, in order: maven user properties, environment variables and JVM
 * system properties.
 */
public final class CommandOptions {

  public static final String GIT_REF = "git.ref";
  public static final String GIT_TAG = "git.tag";
  public static final String GIT_BRANCH = "git.branch";
  static final String DISABLE = "versioning.disable";
  static final String UPDATE_SOURCE_POM = "versioning.updateSourcePom";
  static final String UPDATE_ATTACHED_POM = "versioning.updateAttachedPom";
  static final String CONFIG_FILE_NAME = "versioning.configFile";

  private final GitverSession session;

  public CommandOptions(GitverSession session) {
    this.session = session;
  }

  public @Nullable String get(String name) {
    String value = session.getUserProperties().getProperty(name);
    if (value == null) {
      String plainName = name.replaceFirst("^versioning\\.", "");
      String environmentVariableName =
          "VERSIONING_"
              + String.join("_", plainName.split("(?=\\p{Lu})"))
                  .replaceAll("\\.", "_")
                  .toUpperCase(Locale.ROOT);
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
