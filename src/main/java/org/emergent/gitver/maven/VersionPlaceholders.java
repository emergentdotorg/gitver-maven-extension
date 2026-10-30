package org.emergent.gitver.maven;

import static org.apache.maven.shared.utils.StringUtils.leftPad;
import static org.emergent.gitver.maven.StringUtil.nonNullOrElse;
import static org.emergent.gitver.maven.StringUtil.nonNullOrEmpty;
import static org.emergent.gitver.maven.StringUtil.patternGroupValues;
import static org.emergent.gitver.maven.StringUtil.patternGroups;
import static org.emergent.gitver.maven.VersionUtil.inc;
import static org.emergent.gitver.maven.VersionUtil.matchVersion;
import static org.emergent.gitver.maven.VersionUtil.nextVersion;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.emergent.gitver.git.GitSituation;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

/**
 * Builds the <code>${...}</code> placeholder tables used when formatting versions and properties.
 *
 * <p>Global placeholders depend only on the git state and the maven session; local placeholders
 * additionally depend on the version of the element being rewritten.
 */
final class VersionPlaceholders {

  private VersionPlaceholders() {}

  @NullUnmarked
  static void addLocal(
      Map<String, Supplier<String>> placeholderMap,
      @Nullable Pattern projectVersionPattern,
      String projectVersion) {
    placeholderMap.put("version", Lazy.of(projectVersion));
    // placeholderMap.put("revision", Lazy.of(projectVersion));

    Supplier<Matcher> verComps = Lazy.by(() -> matchVersion(projectVersion));

    placeholderMap.put(
        "version.core", Lazy.by(() -> nonNullOrElse(verComps.get().group("core"), "0.0.0")));

    placeholderMap.put(
        "version.major", Lazy.by(() -> nonNullOrElse(verComps.get().group("major"), "0")));
    placeholderMap.put(
        "version.major.next", Lazy.by(() -> inc(placeholderMap.get("version.major").get(), 1)));

    placeholderMap.put(
        "version.minor", Lazy.by(() -> nonNullOrElse(verComps.get().group("minor"), "0")));
    placeholderMap.put(
        "version.minor.next", Lazy.by(() -> inc(placeholderMap.get("version.minor").get(), 1)));

    placeholderMap.put(
        "version.patch", Lazy.by(() -> nonNullOrElse(verComps.get().group("patch"), "0")));
    placeholderMap.put(
        "version.patch.next", Lazy.by(() -> inc(placeholderMap.get("version.patch").get(), 1)));

    placeholderMap.put(
        "version.build", Lazy.by(() -> nonNullOrElse(verComps.get().group("build"), "")));
    placeholderMap.put(
        "version.build.next", Lazy.by(() -> inc(placeholderMap.get("version.build").get(), 1)));

    placeholderMap.put(
        "version.label", Lazy.by(() -> nonNullOrElse(verComps.get().group("label"), "")));
    placeholderMap.put(
        "version.label.prefixed",
        Lazy.by(
            () -> {
              String label = placeholderMap.get("version.label").get();
              return !label.isEmpty() ? "-" + label : "";
            }));

    placeholderMap.put("version.next", Lazy.by(() -> nextVersion(verComps.get())));

    // deprecated
    placeholderMap.put("version.release", Lazy.by(() -> projectVersion.replaceFirst("-.*$", "")));

    // Now time for placeholders based on the version pattern
    Optional.ofNullable(projectVersionPattern)
        .ifPresent(
            versionPattern -> {
              patternGroupValues(versionPattern, projectVersion)
                  .forEach(
                      (groupName, value) -> {
                        var placeholderKey = "version." + groupName;
                        // ensure no placeholder overwrites
                        if (placeholderMap.containsKey(placeholderKey)) {
                          throw new IllegalArgumentException(
                              "project version pattern capture group can not be named '"
                                  + groupName
                                  + "', because this would overwrite extension placeholder ${"
                                  + placeholderKey
                                  + "}");
                        }
                        placeholderMap.put(
                            placeholderKey, () -> Optional.ofNullable(value).orElse(""));
                      });
            });
  }

  @NullMarked
  static Map<String, Supplier<String>> global(
      GitSituation gitSituation, GitVersionDetails gitVersionDetails, GitverSession mavenSession) {

    Map<String, Supplier<String>> placeholderMap = new HashMap<>();

    Lazy<String> hash = Lazy.by(gitSituation::getRev);
    placeholderMap.put("commit", hash);
    placeholderMap.put("commit.short", Lazy.by(() -> hash.get().substring(0, 7)));

    Lazy<ZonedDateTime> headCommitDateTime = Lazy.by(gitSituation::getTimestamp);
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

    Lazy<ZonedDateTime> buildCommitDateTime =
        Lazy.by(() -> mavenSession.getStart().atZone(ZoneId.systemDefault()));
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

    String refName = gitVersionDetails.refName();
    Lazy<String> refNameSlug = Lazy.by(() -> VersionUtil.slugify(refName));
    placeholderMap.put("ref", () -> refName);
    placeholderMap.put("ref" + ".slug", refNameSlug);

    gitVersionDetails
        .patchOptions()
        .getPattern()
        .map(Pattern::compile)
        .ifPresent(
            refPattern -> {
              // ref pattern groups
              for (Entry<String, @Nullable String> patternGroup :
                  patternGroupValues(refPattern, refName).entrySet()) {
                String groupName = patternGroup.getKey();
                String value = Optional.ofNullable(patternGroup.getValue()).orElse("");

                var placeholderKey = "ref." + groupName;
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
                placeholderMap.put(
                    placeholderKey + ".slug", Lazy.by(() -> VersionUtil.slugify(value)));
              }
            });

    // dirty
    Lazy<Boolean> dirty = Lazy.by(() -> !gitSituation.isClean());
    placeholderMap.put("dirty", Lazy.by(() -> dirty.get() ? ".dirty" : ""));
    placeholderMap.put("dirty.snapshot", Lazy.by(() -> dirty.get() ? "-SNAPSHOT" : ""));

    // describe
    Lazy<GitDescription> description = Lazy.by(gitSituation::getDescription);
    placeholderMap.put("describe", Lazy.by(() -> description.get().toString()));
    Lazy<String> descriptionTag = Lazy.by(() -> description.get().tag());
    placeholderMap.put("describe.tag", descriptionTag);
    Lazy<Integer> descriptionDistance = Lazy.by(() -> description.get().distance());
    placeholderMap.put(
        "describe.distance", Lazy.by(() -> String.valueOf(descriptionDistance.get())));
    placeholderMap.put(
        "describe.distance.snapshot",
        Lazy.by(() -> (descriptionDistance.get() == 0 ? "" : "-SNAPSHOT")));

    placeholderMap.put(
        "describe.snapshot",
        Lazy.by(() -> dirty.get() || descriptionDistance.get() != 0 ? "-SNAPSHOT" : ""));

    Lazy<Matcher> descriptionTagVersionMatcher = Lazy.by(() -> matchVersion(descriptionTag.get()));

    placeholderMap.put(
        "describe.tag.version",
        Lazy.by(() -> nonNullOrElse(descriptionTagVersionMatcher.get().group("version"), "0.0.0")));

    placeholderMap.put(
        "describe.tag.version.core",
        Lazy.by(() -> nonNullOrElse(descriptionTagVersionMatcher.get().group("core"), "0")));

    placeholderMap.put(
        "describe.tag.version.major",
        Lazy.by(() -> nonNullOrElse(descriptionTagVersionMatcher.get().group("major"), "0")));
    placeholderMap.put(
        "describe.tag.version.major.next",
        Lazy.by(() -> inc(placeholderMap.get("describe.tag.version.major").get(), 1)));

    placeholderMap.put(
        "describe.tag.version.minor",
        Lazy.by(() -> nonNullOrElse(descriptionTagVersionMatcher.get().group("minor"), "0")));
    placeholderMap.put(
        "describe.tag.version.minor.next",
        Lazy.by(() -> inc(placeholderMap.get("describe.tag.version.minor").get(), 1)));

    placeholderMap.put(
        "describe.tag.version.patch",
        Lazy.by(() -> nonNullOrElse(descriptionTagVersionMatcher.get().group("patch"), "0")));
    placeholderMap.put(
        "describe.tag.version.patch.next",
        Lazy.by(() -> inc(placeholderMap.get("describe.tag.version.patch").get(), 1)));

    placeholderMap.put(
        "describe.tag.version.build",
        Lazy.by(() -> nonNullOrElse(descriptionTagVersionMatcher.get().group("build"), "")));
    placeholderMap.put(
        "describe.tag.version.build.next",
        Lazy.by(() -> inc(placeholderMap.get("describe.tag.version.build").get(), 1)));

    placeholderMap.put(
        "describe.tag.version.label",
        Lazy.by(() -> nonNullOrElse(descriptionTagVersionMatcher.get().group("label"), "")));
    placeholderMap.put(
        "describe.tag.version.label.next",
        Lazy.by(() -> inc(placeholderMap.get("describe.tag.version.label").get(), 1)));

    placeholderMap.put(
        "describe.tag.version.next",
        Lazy.by(() -> nextVersion(descriptionTagVersionMatcher.get())));

    placeholderMap.put(
        "describe.tag.version.next.snapshot",
        Lazy.by(
            () ->
                placeholderMap.get("describe.tag.version.next").get()
                    + placeholderMap.get("describe.snapshot").get()));

    placeholderMap.put(
        "describe.tag.version.patch.plus.describe.distance",
        Lazy.by(
            () ->
                inc(
                    placeholderMap.get("describe.tag.version.patch").get(),
                    descriptionDistance.get())));
    placeholderMap.put(
        "describe.tag.version.patch.next.plus.describe.distance",
        Lazy.by(
            () ->
                inc(
                    placeholderMap.get("describe.tag.version.patch.next").get(),
                    descriptionDistance.get())));

    placeholderMap.put(
        "describe.tag.version.build.plus.describe.distance",
        Lazy.by(
            () ->
                inc(
                    placeholderMap.get("describe.tag.version.build").get(),
                    descriptionDistance.get())));
    placeholderMap.put(
        "describe.tag.version.build.next.plus.describe.distance",
        Lazy.by(
            () ->
                inc(
                    placeholderMap.get("describe.tag.version.build.next").get(),
                    descriptionDistance.get())));

    placeholderMap.put(
        "describe.tag.version.label.plus.describe.distance",
        Lazy.by(
            () ->
                inc(
                    placeholderMap.get("describe.tag.version.label").get(),
                    descriptionDistance.get())));
    placeholderMap.put(
        "describe.tag.version.label.next.plus.describe.distance",
        Lazy.by(
            () ->
                inc(
                    placeholderMap.get("describe.tag.version.label.next").get(),
                    descriptionDistance.get())));

    placeholderMap.put(
        "describe.tag.version.maven.core",
        Lazy.by(
            () -> {
              String snapshot = placeholderMap.get("describe.snapshot").get();
              ArrayDeque<String> elements =
                  Stream.of(
                          "describe.tag.version.major",
                          "describe.tag.version.minor",
                          "describe.tag.version.patch")
                      .<Supplier<@Nullable String>>map(placeholderMap::get)
                      .map(Supplier::get)
                      .filter(StringUtil::isNotBlank)
                      .collect(Collectors.toCollection(ArrayDeque::new));
              if (StringUtil.isNotEmpty(snapshot)) {
                elements.addLast(inc(elements.removeLast(), 1));
              }
              return String.join(".", elements);
            }));

    placeholderMap.put(
        "describe.tag.version.maven",
        Lazy.by(
            () ->
                placeholderMap.get("describe.tag.version.maven.core").get()
                    + placeholderMap.get("describe.snapshot").get()));

    // describe tag pattern groups
    Lazy<Map<String, @Nullable String>> tagPatternValues =
        Lazy.by(() -> patternGroupValues(gitSituation.getTagPattern(), descriptionTag.get()));
    for (String groupName : patternGroups(gitSituation.getTagPattern())) {
      var placeholderKey = "describe.tag." + groupName;
      // ensure no placeholder overwrites
      if (placeholderMap.containsKey(placeholderKey)) {
        throw new IllegalArgumentException(
            "describe tag pattern capture group can not be named '"
                + groupName
                + "', because this would overwrite extension placeholder ${"
                + placeholderKey
                + "}");
      }
      Lazy<String> groupValue =
          Lazy.by(() -> nonNullOrEmpty(tagPatternValues.get().get(groupName)));
      placeholderMap.put(placeholderKey, groupValue);
      placeholderMap.put(
          placeholderKey + ".slug", Lazy.by(() -> VersionUtil.slugify(groupValue.get())));
    }

    // command parameters e.g. mvn -Dfoo=123 will be available as ${property.foo}
    for (Entry<Object, @Nullable Object> property : mavenSession.getUserProperties().entrySet()) {
      if (property.getValue() != null) {
        placeholderMap.put(
            "property." + property.getKey(), () -> String.valueOf(property.getValue()));
      }
    }

    // environment variables e.g. BUILD_NUMBER=123 will be available as ${env.BUILD_NUMBER}
    System.getenv().forEach((key, value) -> placeholderMap.put("env." + key, () -> value));

    return placeholderMap;
  }
}
