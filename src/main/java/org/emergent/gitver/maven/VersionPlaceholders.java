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

  static Map<String, Supplier<String>> addLocal(
      Map<String, Supplier<String>> placeholders,
      @Nullable Pattern projectVersionPattern,
      String projectVersion) {
    LookupWrapper lookup = new LookupWrapper(placeholders);
    lookup.put("version", Memoized.of(projectVersion));
    // placeholderMap.put("revision", Lazy.of(projectVersion));

    Supplier<Matcher> verComps = by(() -> matchVersion(projectVersion));

    lookup.put("version.core", by(() -> nonNullOrElse(verComps.get().group("core"), "0.0.0")));

    lookup.put("version.major", by(() -> nonNullOrElse(verComps.get().group("major"), "0")));
    lookup.put("version.major.next", by(() -> inc(lookup.get("version.major"))));

    lookup.put("version.minor", by(() -> nonNullOrElse(verComps.get().group("minor"), "0")));
    lookup.put("version.minor.next", by(() -> inc(lookup.get("version.minor"))));

    lookup.put("version.patch", by(() -> nonNullOrElse(verComps.get().group("patch"), "0")));
    lookup.put("version.patch.next", by(() -> inc(lookup.get("version.patch"))));

    lookup.put("version.build", by(() -> nonNullOrElse(verComps.get().group("build"), "")));
    lookup.put("version.build.next", by(() -> inc(lookup.get("version.build"))));

    lookup.put("version.label", by(() -> nonNullOrElse(verComps.get().group("label"), "")));
    lookup.put(
        "version.label.prefixed",
        by(
            () -> {
              String label = lookup.get("version.label");
              return !label.isEmpty() ? "-" + label : "";
            }));

    lookup.put("version.next", by(() -> nextVersion(verComps.get())));

    // deprecated
    lookup.put("version.release", by(() -> projectVersion.replaceFirst("-.*$", "")));

    // Now time for placeholders based on the version pattern
    Optional.ofNullable(projectVersionPattern)
        .ifPresent(
            versionPattern -> {
              patternGroupValues(versionPattern, projectVersion)
                  .forEach(
                      (groupName, value) -> {
                        var placeholderKey = "version." + groupName;
                        // ensure no placeholder overwrites
                        if (lookup.containsKey(placeholderKey)) {
                          throw new IllegalArgumentException(
                              "project version pattern capture group can not be named '"
                                  + groupName
                                  + "', because this would overwrite extension placeholder ${"
                                  + placeholderKey
                                  + "}");
                        }
                        lookup.put(placeholderKey, () -> Optional.ofNullable(value).orElse(""));
                      });
            });
    return lookup.map();
  }

  static Map<String, Supplier<String>> global(
      GitSituation situation, GitVersionDetails versionDetails, GitverSession session) {

    LookupWrapper lookup = new LookupWrapper();

    Memoized<String> hash = by(situation::getRev);
    lookup.put("commit", hash);
    lookup.put("commit.short", by(() -> hash.get().substring(0, 7)));

    Memoized<ZonedDateTime> headCommitDateTime = by(situation::getTimestamp);
    lookup.put(
        "commit.timestamp", by(() -> String.valueOf(headCommitDateTime.get().toEpochSecond())));
    lookup.put(
        "commit.timestamp.year", by(() -> String.valueOf(headCommitDateTime.get().getYear())));
    lookup.put(
        "commit.timestamp.year.2digit",
        by(() -> String.valueOf(headCommitDateTime.get().getYear() % 100)));
    lookup.put(
        "commit.timestamp.month",
        by(() -> leftPad(String.valueOf(headCommitDateTime.get().getMonthValue()), 2, "0")));
    lookup.put(
        "commit.timestamp.day",
        by(() -> leftPad(String.valueOf(headCommitDateTime.get().getDayOfMonth()), 2, "0")));
    lookup.put(
        "commit.timestamp.hour",
        by(() -> leftPad(String.valueOf(headCommitDateTime.get().getHour()), 2, "0")));
    lookup.put(
        "commit.timestamp.minute",
        by(() -> leftPad(String.valueOf(headCommitDateTime.get().getMinute()), 2, "0")));
    lookup.put(
        "commit.timestamp.second",
        by(() -> leftPad(String.valueOf(headCommitDateTime.get().getSecond()), 2, "0")));
    lookup.put(
        "commit.timestamp.datetime",
        by(
            () ->
                headCommitDateTime.get().toEpochSecond() > 0
                    ? headCommitDateTime
                        .get()
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss"))
                    : "00000101.000000"));
    lookup.put(
        "commit.timestamp.iso",
        by(
            () ->
                headCommitDateTime.get().toEpochSecond() > 0
                    ? headCommitDateTime.get().format(DateTimeFormatter.ISO_INSTANT)
                    : "0000-01-01T00:00:00Z"));

    Memoized<ZonedDateTime> buildCommitDateTime =
        by(() -> session.getStart().atZone(ZoneId.systemDefault()));
    lookup.put(
        "build.timestamp", by(() -> String.valueOf(buildCommitDateTime.get().toEpochSecond())));
    lookup.put(
        "build.timestamp.year", by(() -> String.valueOf(buildCommitDateTime.get().getYear())));
    lookup.put(
        "build.timestamp.year.2digit",
        by(() -> String.valueOf(buildCommitDateTime.get().getYear() % 100)));
    lookup.put(
        "build.timestamp.month",
        by(() -> leftPad(String.valueOf(buildCommitDateTime.get().getMonthValue()), 2, "0")));
    lookup.put(
        "build.timestamp.day",
        by(() -> leftPad(String.valueOf(buildCommitDateTime.get().getDayOfMonth()), 2, "0")));
    lookup.put(
        "build.timestamp.hour",
        by(() -> leftPad(String.valueOf(buildCommitDateTime.get().getHour()), 2, "0")));
    lookup.put(
        "build.timestamp.minute",
        by(() -> leftPad(String.valueOf(buildCommitDateTime.get().getMinute()), 2, "0")));
    lookup.put(
        "build.timestamp.second",
        by(() -> leftPad(String.valueOf(buildCommitDateTime.get().getSecond()), 2, "0")));
    lookup.put(
        "build.timestamp.datetime",
        by(
            () ->
                buildCommitDateTime.get().toEpochSecond() > 0
                    ? buildCommitDateTime
                        .get()
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss"))
                    : "00000000.000000"));
    lookup.put(
        "build.timestamp.iso",
        by(
            () ->
                buildCommitDateTime.get().toEpochSecond() > 0
                    ? headCommitDateTime.get().format(DateTimeFormatter.ISO_INSTANT)
                    : "0000-01-01T00:00:00Z"));

    String refName = versionDetails.refName();
    Memoized<String> refNameSlug = by(() -> VersionUtil.slugify(refName));
    lookup.put("ref", () -> refName);
    lookup.put("ref" + ".slug", refNameSlug);

    versionDetails
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
                if (lookup.containsKey(placeholderKey)) {
                  throw new IllegalArgumentException(
                      "ref pattern capture group can not be named '"
                          + groupName
                          + "', because this would overwrite extension placeholder ${"
                          + placeholderKey
                          + "}");
                }
                lookup.put(placeholderKey, () -> value);
                lookup.put(placeholderKey + ".slug", by(() -> VersionUtil.slugify(value)));
              }
            });

    // dirty
    Memoized<Boolean> dirty = by(() -> !situation.isClean());
    lookup.put("dirty", by(() -> dirty.get() ? ".dirty" : ""));
    lookup.put("dirty.snapshot", by(() -> dirty.get() ? "-SNAPSHOT" : ""));

    // describe
    Memoized<GitDescription> description = by(situation::getDescription);
    lookup.put("describe", by(() -> description.get().toString()));
    Memoized<String> descriptionTag = by(() -> description.get().tag());
    lookup.put("describe.tag", descriptionTag);
    Memoized<Integer> descriptionDistance = by(() -> description.get().distance());
    lookup.put("describe.distance", by(() -> String.valueOf(descriptionDistance.get())));
    lookup.put(
        "describe.distance.snapshot",
        by(() -> (descriptionDistance.get() == 0 ? "" : "-SNAPSHOT")));

    lookup.put(
        "describe.snapshot",
        by(() -> dirty.get() || descriptionDistance.get() != 0 ? "-SNAPSHOT" : ""));

    Memoized<Matcher> descriptionTagVersionMatcher = by(() -> matchVersion(descriptionTag.get()));

    lookup.put(
        "describe.tag.version",
        by(() -> nonNullOrElse(descriptionTagVersionMatcher.get().group("version"), "0.0.0")));

    lookup.put(
        "describe.tag.version.core",
        by(() -> nonNullOrElse(descriptionTagVersionMatcher.get().group("core"), "0")));

    lookup.put(
        "describe.tag.version.major",
        by(() -> nonNullOrElse(descriptionTagVersionMatcher.get().group("major"), "0")));
    lookup.put(
        "describe.tag.version.major.next", by(() -> inc(lookup.get("describe.tag.version.major"))));

    lookup.put(
        "describe.tag.version.minor",
        by(() -> nonNullOrElse(descriptionTagVersionMatcher.get().group("minor"), "0")));
    lookup.put(
        "describe.tag.version.minor.next", by(() -> inc(lookup.get("describe.tag.version.minor"))));

    lookup.put(
        "describe.tag.version.patch",
        by(() -> nonNullOrElse(descriptionTagVersionMatcher.get().group("patch"), "0")));
    lookup.put(
        "describe.tag.version.patch.next", by(() -> inc(lookup.get("describe.tag.version.patch"))));

    lookup.put(
        "describe.tag.version.build",
        by(() -> nonNullOrElse(descriptionTagVersionMatcher.get().group("build"), "")));
    lookup.put(
        "describe.tag.version.build.next", by(() -> inc(lookup.get("describe.tag.version.build"))));

    lookup.put(
        "describe.tag.version.label",
        by(() -> nonNullOrElse(descriptionTagVersionMatcher.get().group("label"), "")));
    lookup.put(
        "describe.tag.version.label.next", by(() -> inc(lookup.get("describe.tag.version.label"))));

    lookup.put(
        "describe.tag.version.next", by(() -> nextVersion(descriptionTagVersionMatcher.get())));

    lookup.put(
        "describe.tag.version.next.snapshot",
        by(() -> lookup.get("describe.tag.version.next") + lookup.get("describe.snapshot")));

    lookup.put(
        "describe.tag.version.patch.plus.describe.distance",
        by(() -> inc(lookup.get("describe.tag.version.patch"), descriptionDistance.get())));
    lookup.put(
        "describe.tag.version.patch.next.plus.describe.distance",
        by(() -> inc(lookup.get("describe.tag.version.patch.next"), descriptionDistance.get())));

    lookup.put(
        "describe.tag.version.build.plus.describe.distance",
        by(() -> inc(lookup.get("describe.tag.version.build"), descriptionDistance.get())));
    lookup.put(
        "describe.tag.version.build.next.plus.describe.distance",
        by(() -> inc(lookup.get("describe.tag.version.build.next"), descriptionDistance.get())));

    lookup.put(
        "describe.tag.version.label.plus.describe.distance",
        by(() -> inc(lookup.get("describe.tag.version.label"), descriptionDistance.get())));
    lookup.put(
        "describe.tag.version.label.next.plus.describe.distance",
        by(() -> inc(lookup.get("describe.tag.version.label.next"), descriptionDistance.get())));

    lookup.put(
        "describe.tag.version.maven.core",
        by(
            () -> {
              String snapshot = lookup.get("describe.snapshot");
              ArrayDeque<String> elements =
                  Stream.of(
                          "describe.tag.version.major",
                          "describe.tag.version.minor",
                          "describe.tag.version.patch")
                      .map(lookup::get)
                      .filter(StringUtil::isNotBlank)
                      .collect(Collectors.toCollection(ArrayDeque::new));
              if (StringUtil.isNotEmpty(snapshot)) {
                elements.addLast(inc(elements.removeLast(), 1));
              }
              return String.join(".", elements);
            }));

    lookup.put(
        "describe.tag.version.maven",
        by(() -> lookup.get("describe.tag.version.maven.core") + lookup.get("describe.snapshot")));

    // describe tag pattern groups
    Memoized<Map<String, @Nullable String>> tagPatternValues =
        by(() -> patternGroupValues(situation.getTagPattern(), descriptionTag.get()));
    for (String groupName : patternGroups(situation.getTagPattern())) {
      var placeholderKey = "describe.tag." + groupName;
      // ensure no placeholder overwrites
      if (lookup.containsKey(placeholderKey)) {
        throw new IllegalArgumentException(
            "describe tag pattern capture group can not be named '"
                + groupName
                + "', because this would overwrite extension placeholder ${"
                + placeholderKey
                + "}");
      }
      Memoized<String> groupValue = by(() -> nonNullOrEmpty(tagPatternValues.get().get(groupName)));
      lookup.put(placeholderKey, groupValue);
      lookup.put(placeholderKey + ".slug", by(() -> VersionUtil.slugify(groupValue.get())));
    }

    // command parameters e.g. mvn -Dfoo=123 will be available as ${property.foo}
    for (Entry<Object, @Nullable Object> property : session.getUserProperties().entrySet()) {
      if (property.getValue() != null) {
        lookup.put("property." + property.getKey(), () -> String.valueOf(property.getValue()));
      }
    }

    // environment variables e.g. BUILD_NUMBER=123 will be available as ${env.BUILD_NUMBER}
    System.getenv().forEach((key, value) -> lookup.put("env." + key, () -> value));

    return lookup.map();
  }

  private static <T> Memoized<T> by(Supplier<T> supplier) {
    return Memoized.by(supplier);
  }

  record LookupWrapper(Map<String, Supplier<String>> map) {
    public LookupWrapper() {
      this(new HashMap<>());
    }

    public String get(String key) {
      return map.getOrDefault(key, () -> "").get();
    }

    public void put(String key, Supplier<String> supplier) {
      map.put(key, supplier);
    }

    public boolean containsKey(String key) {
      return map.containsKey(key);
    }

    @SuppressWarnings("MethodDoesntCallSuperMethod")
    @Override
    public LookupWrapper clone() {
      return new LookupWrapper(new HashMap<>(map));
    }

    public static String toString(int num) {
      return String.valueOf(num);
    }

    public static String toString(long num) {
      return String.valueOf(num);
    }
  }
}
