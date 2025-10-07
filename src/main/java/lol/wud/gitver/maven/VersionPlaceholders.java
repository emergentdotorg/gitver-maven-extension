package lol.wud.gitver.maven;

import static java.time.format.DateTimeFormatter.ISO_INSTANT;
import static lol.wud.gitver.maven.VersionUtil.inc;
import static lol.wud.gitver.maven.VersionUtil.incLabel;
import static lol.wud.gitver.maven.VersionUtil.matchVersion;
import static lol.wud.gitver.maven.VersionUtil.nextVersion;
import static lol.wud.gitver.util.StringUtil.nonNullOrElse;
import static lol.wud.gitver.util.StringUtil.nonNullOrEmpty;
import static lol.wud.gitver.util.StringUtil.patternGroupValues;
import static lol.wud.gitver.util.StringUtil.patternGroups;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lol.wud.gitver.git.GitDescription;
import lol.wud.gitver.git.GitSituation;
import lol.wud.gitver.util.Memoized;
import lol.wud.gitver.util.StringUtil;
import org.jspecify.annotations.Nullable;

/**
 * Builds the <code>${...}</code> placeholder tables used when formatting versions and properties.
 *
 * <p>Global placeholders depend only on the git state and the maven session; local placeholders
 * additionally depend on the version of the element being rewritten.
 */
final class VersionPlaceholders {

  private static final DateTimeFormatter DATETIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss");

  private VersionPlaceholders() {}

  static Map<String, Supplier<String>> addLocal(
      Map<String, Supplier<String>> placeholders,
      @Nullable Pattern projectVersionPattern,
      String projectVersion) {
    LookupWrapper lookup = new LookupWrapper(placeholders);
    lookup.put("version", Memoized.of(projectVersion));
    putVersionParts(lookup, "version", by(() -> matchVersion(projectVersion)));

    // deprecated
    lookup.put("version.release", by(() -> projectVersion.replaceFirst("-.*$", "")));

    if (projectVersionPattern != null) {
      patternGroupValues(projectVersionPattern, projectVersion)
          .forEach(
              (groupName, value) ->
                  putGroup(
                      lookup,
                      "project version pattern",
                      "version.",
                      groupName,
                      () -> nonNullOrEmpty(value)));
    }
    return lookup.map();
  }

  static Map<String, Supplier<String>> global(
      GitSituation situation, GitVersionDetails versionDetails, GitverSession session) {

    LookupWrapper lookup = new LookupWrapper();

    Memoized<String> hash = by(situation::getRev);
    lookup.put("commit", hash);
    lookup.put("commit.short", by(() -> hash.get().substring(0, 7)));

    putTimestamps(lookup, "commit.timestamp", situation::getTimestamp);
    putTimestamps(
        lookup, "build.timestamp", () -> session.getStart().atZone(ZoneId.systemDefault()));

    String refName = versionDetails.refName();
    lookup.put("ref", () -> refName);
    lookup.put("ref.slug", by(() -> VersionUtil.slugify(refName)));
    versionDetails
        .patchOptions()
        .getPattern()
        .map(Pattern::compile)
        .ifPresent(
            refPattern -> {
              for (Entry<String, @Nullable String> group :
                  patternGroupValues(refPattern, refName).entrySet()) {
                String value = nonNullOrEmpty(group.getValue());
                putGroup(lookup, "ref pattern", "ref.", group.getKey(), () -> value);
                lookup.put("ref." + group.getKey() + ".slug", by(() -> VersionUtil.slugify(value)));
              }
            });

    Memoized<Boolean> dirty = by(() -> !situation.isClean());
    lookup.put("dirty", by(() -> dirty.get() ? ".dirty" : ""));
    lookup.put("dirty.snapshot", by(() -> dirty.get() ? "-SNAPSHOT" : ""));

    putDescribe(lookup, situation, dirty);

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

  /** Formats <code>time</code> as an ISO instant, or a fixed placeholder for an unknown time. */
  static String isoTimestamp(ZonedDateTime time) {
    return time.toEpochSecond() > 0 ? time.format(ISO_INSTANT) : "0000-01-01T00:00:00Z";
  }

  private static void putDescribe(
      LookupWrapper lookup, GitSituation situation, Supplier<Boolean> dirty) {
    Memoized<GitDescription> description = by(situation::getDescription);
    Memoized<String> tag = by(() -> description.get().tag());
    Memoized<Integer> distance = by(() -> description.get().distance());

    lookup.put("describe", by(() -> description.get().toString()));
    lookup.put("describe.tag", tag);
    lookup.put("describe.distance", by(() -> String.valueOf(distance.get())));
    lookup.put("describe.distance.snapshot", by(() -> distance.get() == 0 ? "" : "-SNAPSHOT"));
    lookup.put(
        "describe.snapshot", by(() -> dirty.get() || distance.get() != 0 ? "-SNAPSHOT" : ""));

    String prefix = "describe.tag.version";
    Memoized<Matcher> tagVersion = by(() -> matchVersion(tag.get()));
    lookup.put(prefix, by(() -> nonNullOrElse(tagVersion.get().group("version"), "0.0.0")));
    putVersionParts(lookup, prefix, tagVersion);
    lookup.put(
        prefix + ".next.snapshot",
        by(() -> lookup.get(prefix + ".next") + lookup.get("describe.snapshot")));

    for (String part : List.of("patch", "patch.next", "build", "build.next")) {
      String key = prefix + "." + part;
      lookup.put(key + ".plus.describe.distance", by(() -> inc(lookup.get(key), distance.get())));
    }
    for (String part : List.of("label", "label.next")) {
      String key = prefix + "." + part;
      lookup.put(
          key + ".plus.describe.distance", by(() -> incLabel(lookup.get(key), distance.get())));
    }

    lookup.put(
        prefix + ".maven.core",
        by(
            () -> {
              ArrayDeque<String> elements =
                  Stream.of("major", "minor", "patch")
                      .map(part -> lookup.get(prefix + "." + part))
                      .filter(StringUtil::isNotBlank)
                      .collect(Collectors.toCollection(ArrayDeque::new));
              if (StringUtil.isNotEmpty(lookup.get("describe.snapshot"))) {
                elements.addLast(inc(elements.removeLast(), 1));
              }
              return String.join(".", elements);
            }));
    lookup.put(
        prefix + ".maven",
        by(() -> lookup.get(prefix + ".maven.core") + lookup.get("describe.snapshot")));

    // describe tag pattern groups
    Pattern tagPattern = situation.getTagPattern();
    Memoized<Map<String, @Nullable String>> tagPatternValues =
        by(() -> patternGroupValues(tagPattern, tag.get()));
    for (String groupName : patternGroups(tagPattern)) {
      Memoized<String> value = by(() -> nonNullOrEmpty(tagPatternValues.get().get(groupName)));
      putGroup(lookup, "describe tag pattern", "describe.tag.", groupName, value);
      lookup.put("describe.tag." + groupName + ".slug", by(() -> VersionUtil.slugify(value.get())));
    }
  }

  /** Adds the components of a {@link VersionUtil#matchVersion} result below <code>prefix</code>. */
  private static void putVersionParts(
      LookupWrapper lookup, String prefix, Supplier<Matcher> version) {
    lookup.put(prefix + ".core", by(() -> nonNullOrElse(version.get().group("core"), "0.0.0")));
    for (String part : List.of("major", "minor", "patch")) {
      String key = prefix + "." + part;
      lookup.put(key, by(() -> nonNullOrElse(version.get().group(part), "0")));
      lookup.put(key + ".next", by(() -> inc(lookup.get(key))));
    }

    String build = prefix + ".build";
    lookup.put(build, by(() -> nonNullOrEmpty(version.get().group("build"))));
    lookup.put(build + ".next", by(() -> inc(lookup.get(build))));

    String label = prefix + ".label";
    lookup.put(label, by(() -> nonNullOrEmpty(version.get().group("label"))));
    lookup.put(label + ".next", by(() -> incLabel(lookup.get(label), 1)));
    lookup.put(
        label + ".prefixed", by(() -> lookup.get(label).isEmpty() ? "" : "-" + lookup.get(label)));

    lookup.put(prefix + ".next", by(() -> nextVersion(version.get())));
  }

  private static void putTimestamps(
      LookupWrapper lookup, String prefix, Supplier<ZonedDateTime> timestamp) {
    Memoized<ZonedDateTime> time = by(timestamp);
    lookup.put(prefix, by(() -> String.valueOf(time.get().toEpochSecond())));
    lookup.put(prefix + ".year", by(() -> String.valueOf(time.get().getYear())));
    lookup.put(prefix + ".year.2digit", by(() -> twoDigits(time.get().getYear() % 100)));
    lookup.put(prefix + ".month", by(() -> twoDigits(time.get().getMonthValue())));
    lookup.put(prefix + ".day", by(() -> twoDigits(time.get().getDayOfMonth())));
    lookup.put(prefix + ".hour", by(() -> twoDigits(time.get().getHour())));
    lookup.put(prefix + ".minute", by(() -> twoDigits(time.get().getMinute())));
    lookup.put(prefix + ".second", by(() -> twoDigits(time.get().getSecond())));
    lookup.put(
        prefix + ".datetime",
        by(
            () ->
                time.get().toEpochSecond() > 0
                    ? time.get().format(DATETIME_FORMAT)
                    : "00000101.000000"));
    lookup.put(prefix + ".iso", by(() -> isoTimestamp(time.get())));
  }

  /** Adds a placeholder for a capture group, refusing to shadow a built-in placeholder. */
  private static void putGroup(
      LookupWrapper lookup,
      String patternName,
      String prefix,
      String groupName,
      Supplier<String> value) {
    String key = prefix + groupName;
    if (lookup.containsKey(key)) {
      throw new IllegalArgumentException(
          "%s capture group can not be named '%s', because this would overwrite extension"
                  .formatted(patternName, groupName)
              + " placeholder ${%s}".formatted(key));
    }
    lookup.put(key, value);
  }

  private static String twoDigits(int value) {
    return String.format("%02d", value);
  }

  private static <T> Memoized<T> by(Supplier<T> supplier) {
    return Memoized.by(supplier);
  }

  record LookupWrapper(Map<String, Supplier<String>> map) {
    LookupWrapper() {
      this(new HashMap<>());
    }

    String get(String key) {
      return map.getOrDefault(key, () -> "").get();
    }

    void put(String key, Supplier<String> supplier) {
      map.put(key, supplier);
    }

    boolean containsKey(String key) {
      return map.containsKey(key);
    }
  }
}
