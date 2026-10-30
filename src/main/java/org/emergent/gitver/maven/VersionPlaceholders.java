package org.emergent.gitver.maven;

import static org.apache.maven.shared.utils.StringUtils.leftPad;
import static org.emergent.gitver.maven.VersionUtil.inc;
import static org.emergent.gitver.maven.VersionUtil.matchVersion;
import static org.emergent.gitver.maven.VersionUtil.nextVersion;
import static org.emergent.gitver.util.StringUtil.nonNullOrElse;
import static org.emergent.gitver.util.StringUtil.nonNullOrEmpty;
import static org.emergent.gitver.util.StringUtil.patternGroupValues;
import static org.emergent.gitver.util.StringUtil.patternGroups;

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
import org.emergent.gitver.git.GitDescription;
import org.emergent.gitver.git.GitSituation;
import org.emergent.gitver.util.Memoized;
import org.emergent.gitver.util.StringUtil;
import org.jspecify.annotations.Nullable;

/**
 * Builds the <code>${...}</code> placeholder tables used when formatting versions and properties.
 *
 * <p>Global placeholders depend only on the git state and the maven session; local placeholders
 * additionally depend on the version of the element being rewritten.
 */
final class VersionPlaceholders {

  private VersionPlaceholders() {}

  static void addLocal(
      Map<String, Supplier<String>> placeholderMap,
      @Nullable Pattern projectVersionPattern,
      String projectVersion) {
    placeholderMap.put("version", Memoized.of(projectVersion));
    // placeholderMap.put("revision", Lazy.of(projectVersion));

    Supplier<Matcher> verComps = by(() -> matchVersion(projectVersion));

    placeholderMap.put(
        "version.core", by(() -> nonNullOrElse(verComps.get().group("core"), "0.0.0")));

    placeholderMap.put(
        "version.major", by(() -> nonNullOrElse(verComps.get().group("major"), "0")));
    placeholderMap.put(
        "version.major.next", by(() -> inc(placeholderMap.get("version.major").get(), 1)));

    placeholderMap.put(
        "version.minor", by(() -> nonNullOrElse(verComps.get().group("minor"), "0")));
    placeholderMap.put(
        "version.minor.next", by(() -> inc(placeholderMap.get("version.minor").get(), 1)));

    placeholderMap.put(
        "version.patch", by(() -> nonNullOrElse(verComps.get().group("patch"), "0")));
    placeholderMap.put(
        "version.patch.next", by(() -> inc(placeholderMap.get("version.patch").get(), 1)));

    placeholderMap.put("version.build", by(() -> nonNullOrElse(verComps.get().group("build"), "")));
    placeholderMap.put(
        "version.build.next", by(() -> inc(placeholderMap.get("version.build").get(), 1)));

    placeholderMap.put("version.label", by(() -> nonNullOrElse(verComps.get().group("label"), "")));
    placeholderMap.put(
        "version.label.prefixed",
        by(
            () -> {
              String label = placeholderMap.get("version.label").get();
              return !label.isEmpty() ? "-" + label : "";
            }));

    placeholderMap.put("version.next", by(() -> nextVersion(verComps.get())));

    // deprecated
    placeholderMap.put("version.release", by(() -> projectVersion.replaceFirst("-.*$", "")));

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

  static Map<String, Supplier<String>> global(
      GitSituation gitSituation, GitVersionDetails gitVersionDetails, GitverSession mavenSession) {

    Map<String, Supplier<String>> placeholderMap = new HashMap<>();

    Memoized<String> hash = by(gitSituation::getRev);
    placeholderMap.put("commit", hash);
    placeholderMap.put("commit.short", by(() -> hash.get().substring(0, 7)));

    Memoized<ZonedDateTime> headCommitDateTime = by(gitSituation::getTimestamp);
    placeholderMap.put(
        "commit.timestamp", by(() -> String.valueOf(headCommitDateTime.get().toEpochSecond())));
    placeholderMap.put(
        "commit.timestamp.year", by(() -> String.valueOf(headCommitDateTime.get().getYear())));
    placeholderMap.put(
        "commit.timestamp.year.2digit",
        by(() -> String.valueOf(headCommitDateTime.get().getYear() % 100)));
    placeholderMap.put(
        "commit.timestamp.month",
        by(() -> leftPad(String.valueOf(headCommitDateTime.get().getMonthValue()), 2, "0")));
    placeholderMap.put(
        "commit.timestamp.day",
        by(() -> leftPad(String.valueOf(headCommitDateTime.get().getDayOfMonth()), 2, "0")));
    placeholderMap.put(
        "commit.timestamp.hour",
        by(() -> leftPad(String.valueOf(headCommitDateTime.get().getHour()), 2, "0")));
    placeholderMap.put(
        "commit.timestamp.minute",
        by(() -> leftPad(String.valueOf(headCommitDateTime.get().getMinute()), 2, "0")));
    placeholderMap.put(
        "commit.timestamp.second",
        by(() -> leftPad(String.valueOf(headCommitDateTime.get().getSecond()), 2, "0")));
    placeholderMap.put(
        "commit.timestamp.datetime",
        by(
            () ->
                headCommitDateTime.get().toEpochSecond() > 0
                    ? headCommitDateTime
                        .get()
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss"))
                    : "00000101.000000"));
    placeholderMap.put(
        "commit.timestamp.iso",
        by(
            () ->
                headCommitDateTime.get().toEpochSecond() > 0
                    ? headCommitDateTime.get().format(DateTimeFormatter.ISO_INSTANT)
                    : "0000-01-01T00:00:00Z"));

    Memoized<ZonedDateTime> buildCommitDateTime =
        by(() -> mavenSession.getStart().atZone(ZoneId.systemDefault()));
    placeholderMap.put(
        "build.timestamp", by(() -> String.valueOf(buildCommitDateTime.get().toEpochSecond())));
    placeholderMap.put(
        "build.timestamp.year", by(() -> String.valueOf(buildCommitDateTime.get().getYear())));
    placeholderMap.put(
        "build.timestamp.year.2digit",
        by(() -> String.valueOf(buildCommitDateTime.get().getYear() % 100)));
    placeholderMap.put(
        "build.timestamp.month",
        by(() -> leftPad(String.valueOf(buildCommitDateTime.get().getMonthValue()), 2, "0")));
    placeholderMap.put(
        "build.timestamp.day",
        by(() -> leftPad(String.valueOf(buildCommitDateTime.get().getDayOfMonth()), 2, "0")));
    placeholderMap.put(
        "build.timestamp.hour",
        by(() -> leftPad(String.valueOf(buildCommitDateTime.get().getHour()), 2, "0")));
    placeholderMap.put(
        "build.timestamp.minute",
        by(() -> leftPad(String.valueOf(buildCommitDateTime.get().getMinute()), 2, "0")));
    placeholderMap.put(
        "build.timestamp.second",
        by(() -> leftPad(String.valueOf(buildCommitDateTime.get().getSecond()), 2, "0")));
    placeholderMap.put(
        "build.timestamp.datetime",
        by(
            () ->
                buildCommitDateTime.get().toEpochSecond() > 0
                    ? buildCommitDateTime
                        .get()
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss"))
                    : "00000000.000000"));
    placeholderMap.put(
        "build.timestamp.iso",
        by(
            () ->
                buildCommitDateTime.get().toEpochSecond() > 0
                    ? headCommitDateTime.get().format(DateTimeFormatter.ISO_INSTANT)
                    : "0000-01-01T00:00:00Z"));

    String refName = gitVersionDetails.refName();
    Memoized<String> refNameSlug = by(() -> VersionUtil.slugify(refName));
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
                placeholderMap.put(placeholderKey + ".slug", by(() -> VersionUtil.slugify(value)));
              }
            });

    // dirty
    Memoized<Boolean> dirty = by(() -> !gitSituation.isClean());
    placeholderMap.put("dirty", by(() -> dirty.get() ? ".dirty" : ""));
    placeholderMap.put("dirty.snapshot", by(() -> dirty.get() ? "-SNAPSHOT" : ""));

    // describe
    Memoized<GitDescription> description = by(gitSituation::getDescription);
    placeholderMap.put("describe", by(() -> description.get().toString()));
    Memoized<String> descriptionTag = by(() -> description.get().tag());
    placeholderMap.put("describe.tag", descriptionTag);
    Memoized<Integer> descriptionDistance = by(() -> description.get().distance());
    placeholderMap.put("describe.distance", by(() -> String.valueOf(descriptionDistance.get())));
    placeholderMap.put(
        "describe.distance.snapshot",
        by(() -> (descriptionDistance.get() == 0 ? "" : "-SNAPSHOT")));

    placeholderMap.put(
        "describe.snapshot",
        by(() -> dirty.get() || descriptionDistance.get() != 0 ? "-SNAPSHOT" : ""));

    Memoized<Matcher> descriptionTagVersionMatcher = by(() -> matchVersion(descriptionTag.get()));

    placeholderMap.put(
        "describe.tag.version",
        by(() -> nonNullOrElse(descriptionTagVersionMatcher.get().group("version"), "0.0.0")));

    placeholderMap.put(
        "describe.tag.version.core",
        by(() -> nonNullOrElse(descriptionTagVersionMatcher.get().group("core"), "0")));

    placeholderMap.put(
        "describe.tag.version.major",
        by(() -> nonNullOrElse(descriptionTagVersionMatcher.get().group("major"), "0")));
    placeholderMap.put(
        "describe.tag.version.major.next",
        by(() -> inc(placeholderMap.get("describe.tag.version.major").get(), 1)));

    placeholderMap.put(
        "describe.tag.version.minor",
        by(() -> nonNullOrElse(descriptionTagVersionMatcher.get().group("minor"), "0")));
    placeholderMap.put(
        "describe.tag.version.minor.next",
        by(() -> inc(placeholderMap.get("describe.tag.version.minor").get(), 1)));

    placeholderMap.put(
        "describe.tag.version.patch",
        by(() -> nonNullOrElse(descriptionTagVersionMatcher.get().group("patch"), "0")));
    placeholderMap.put(
        "describe.tag.version.patch.next",
        by(() -> inc(placeholderMap.get("describe.tag.version.patch").get(), 1)));

    placeholderMap.put(
        "describe.tag.version.build",
        by(() -> nonNullOrElse(descriptionTagVersionMatcher.get().group("build"), "")));
    placeholderMap.put(
        "describe.tag.version.build.next",
        by(() -> inc(placeholderMap.get("describe.tag.version.build").get(), 1)));

    placeholderMap.put(
        "describe.tag.version.label",
        by(() -> nonNullOrElse(descriptionTagVersionMatcher.get().group("label"), "")));
    placeholderMap.put(
        "describe.tag.version.label.next",
        by(() -> inc(placeholderMap.get("describe.tag.version.label").get(), 1)));

    placeholderMap.put(
        "describe.tag.version.next", by(() -> nextVersion(descriptionTagVersionMatcher.get())));

    placeholderMap.put(
        "describe.tag.version.next.snapshot",
        by(
            () ->
                placeholderMap.get("describe.tag.version.next").get()
                    + placeholderMap.get("describe.snapshot").get()));

    placeholderMap.put(
        "describe.tag.version.patch.plus.describe.distance",
        by(
            () ->
                inc(
                    placeholderMap.get("describe.tag.version.patch").get(),
                    descriptionDistance.get())));
    placeholderMap.put(
        "describe.tag.version.patch.next.plus.describe.distance",
        by(
            () ->
                inc(
                    placeholderMap.get("describe.tag.version.patch.next").get(),
                    descriptionDistance.get())));

    placeholderMap.put(
        "describe.tag.version.build.plus.describe.distance",
        by(
            () ->
                inc(
                    placeholderMap.get("describe.tag.version.build").get(),
                    descriptionDistance.get())));
    placeholderMap.put(
        "describe.tag.version.build.next.plus.describe.distance",
        by(
            () ->
                inc(
                    placeholderMap.get("describe.tag.version.build.next").get(),
                    descriptionDistance.get())));

    placeholderMap.put(
        "describe.tag.version.label.plus.describe.distance",
        by(
            () ->
                inc(
                    placeholderMap.get("describe.tag.version.label").get(),
                    descriptionDistance.get())));
    placeholderMap.put(
        "describe.tag.version.label.next.plus.describe.distance",
        by(
            () ->
                inc(
                    placeholderMap.get("describe.tag.version.label.next").get(),
                    descriptionDistance.get())));

    placeholderMap.put(
        "describe.tag.version.maven.core",
        by(
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
        by(
            () ->
                placeholderMap.get("describe.tag.version.maven.core").get()
                    + placeholderMap.get("describe.snapshot").get()));

    // describe tag pattern groups
    Memoized<Map<String, @Nullable String>> tagPatternValues =
        by(() -> patternGroupValues(gitSituation.getTagPattern(), descriptionTag.get()));
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
      Memoized<String> groupValue = by(() -> nonNullOrEmpty(tagPatternValues.get().get(groupName)));
      placeholderMap.put(placeholderKey, groupValue);
      placeholderMap.put(placeholderKey + ".slug", by(() -> VersionUtil.slugify(groupValue.get())));
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

  private static <T> Memoized<T> by(Supplier<T> supplier) {
    return Memoized.by(supplier);
  }
}
