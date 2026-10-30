package org.emergent.gitver.maven;

import static org.emergent.gitver.maven.StringUtil.nonNullOrEmpty;
import static org.emergent.gitver.maven.StringUtil.substituteText;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.emergent.gitver.git.GitSituation;
import org.jspecify.annotations.Nullable;

/**
 * Turns version and property format strings (e.g. <code>${describe.tag.version}</code>) into
 * concrete values. This class is the only place where version calculation happens; it knows nothing
 * about the maven model or the files being written.
 */
final class VersionFormatter {

  @Nullable private final Pattern projectVersionPattern;
  private final Map<String, Supplier<String>> globalPlaceholders;

  VersionFormatter(
      GitSituation gitSituation,
      GitVersionDetails gitVersionDetails,
      GitverSession mavenSession,
      Configuration config) {
    this.projectVersionPattern =
        config.getProjectVersionPattern().map(Pattern::compile).orElse(null);
    this.globalPlaceholders =
        VersionPlaceholders.global(gitSituation, gitVersionDetails, mavenSession);
  }

  /** Formats the version that replaces <code>projectVersion</code>. */
  String formatVersion(String versionFormat, String projectVersion) {
    return VersionUtil.slugify(substituteText(versionFormat, placeholders(projectVersion)));
  }

  /** Formats the value of a property whose current value is <code>originalValue</code>. */
  String formatProperty(Object propertyFormat, String originalValue, String projectVersion) {
    Map<String, Supplier<String>> placeholders = placeholders(projectVersion);
    placeholders.put("value", () -> originalValue);
    return substituteText(String.valueOf(propertyFormat), placeholders);
  }

  private Map<String, Supplier<String>> placeholders(String projectVersion) {
    Map<String, Supplier<String>> map = nonNullOrEmpty(new HashMap<>(globalPlaceholders));
    VersionPlaceholders.addLocal(map, projectVersionPattern, projectVersion);
    return map;
  }
}
