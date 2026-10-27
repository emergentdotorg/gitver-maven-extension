package org.emergent.gittle.maven;

import static org.apache.maven.shared.utils.StringUtils.leftPad;
import static org.emergent.gittle.maven.StringUtil.patternGroupValues;
import static org.emergent.gittle.maven.StringUtil.patternGroups;
import static org.emergent.gittle.maven.StringUtil.substituteText;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

@Value
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
// @lombok.Builder(toBuilder = true, setterPrefix = "set")
class PropsUtil {

  static final Pattern VERSION_PATTERN =
      Pattern.compile(
          ".*?(?<version>"
              + "(?<core>(?<major>\\d+)(?:\\.(?<minor>\\d+)(?:\\.(?<patch>\\d+))?)?)"
              + "(?:\\.(?<build>\\d+))?(?:-(?<label>.*))?"
              + ")|");

  private static final Pattern LABEL_TRAILING_NUM = Pattern.compile("^(.*?)(\\d*)$");

  public static PropsUtil create(
      GitSituation gitSituation,
      GitVersionDetails gitVersionDetails,
      GittleSession mavenSession,
      Configuration config) {
    return new PropsUtil(gitSituation, gitVersionDetails, mavenSession, config);
  }

  // GitSituation gitSituation;
  // GitVersionDetails gitVersionDetails;
  // MavenSession mavenSession;
  Configuration config;
  Map<String, Supplier<String>> globalFormatPlaceholderMap;

  public PropsUtil(
      GitSituation gitSituation,
      GitVersionDetails gitVersionDetails,
      GittleSession mavenSession,
      Configuration config) {
    this(config, generateGlobalFormatPlaceholderMap(gitSituation, gitVersionDetails, mavenSession));
  }

  public String getGitVersion(String versionFormat, String projectVersion) {
    final Map<String, Supplier<String>> placeholderMap =
        generateFormatPlaceholderMap(projectVersion);
    return ProcessorUtil.slugify(substituteText(versionFormat, placeholderMap));
  }

  public String getGitPropertyValue(
      Object propertyFormat, String originalValue, String projectVersion) {
    final Map<String, Supplier<String>> placeholderMap =
        generateFormatPlaceholderMap(projectVersion);
    placeholderMap.put("value", () -> originalValue);
    return substituteText(String.valueOf(propertyFormat), placeholderMap);
  }

  @NullUnmarked
  private Map<String, Supplier<String>> generateFormatPlaceholderMap(String projectVersion) {
    return addLocalPlaceHolders(
        new HashMap<>(globalFormatPlaceholderMap),
        Optional.ofNullable(config.projectVersionPattern).map(Pattern::compile).orElse(null),
        projectVersion);
  }

  private static Map<String, Supplier<@Nullable String>> addLocalPlaceHolders(
      Map<String, Supplier<@Nullable String>> placeholderMap,
      @Nullable Pattern projectVersionPattern,
      String projectVersion) {
    Map<String, Supplier<String>> map = nonNullOrEmpty(placeholderMap);
    addVersionLocalVersionPlaceHolders(map, projectVersionPattern, projectVersion);
    return map;
  }

  @NullUnmarked
  static void addVersionLocalVersionPlaceHolders(
      Map<String, Supplier<String>> placeholderMap,
      @Nullable Pattern projectVersionPattern,
      String projectVersion) {
    placeholderMap.put("version", Lazy.of(projectVersion));
    placeholderMap.put("revision", Lazy.of(projectVersion));

    final Supplier<Matcher> verComps = Lazy.by(() -> matchVersion(projectVersion));

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
  static Map<String, Supplier<String>> generateGlobalFormatPlaceholderMap(
      GitSituation gitSituation, GitVersionDetails gitVersionDetails, GittleSession mavenSession) {

    final Map<String, Supplier<String>> placeholderMap = new HashMap<>();

    final Lazy<String> hash = Lazy.by(gitSituation::getRev);
    placeholderMap.put("commit", hash);
    placeholderMap.put("commit.short", Lazy.by(() -> hash.get().substring(0, 7)));

    final Lazy<ZonedDateTime> headCommitDateTime = Lazy.by(gitSituation::getTimestamp);
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

    final Lazy<ZonedDateTime> buildCommitDateTime =
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

    final String refName = gitVersionDetails.refName();
    final Lazy<String> refNameSlug = Lazy.by(() -> ProcessorUtil.slugify(refName));
    placeholderMap.put("ref", () -> refName);
    placeholderMap.put("ref" + ".slug", refNameSlug);

    final Pattern refPattern =
        Optional.ofNullable(gitVersionDetails.patchDescription().pattern)
            .map(Pattern::compile)
            .orElse(null);
    if (refPattern != null) {
      // ref pattern groups
      for (Entry<String, @Nullable String> patternGroup :
          patternGroupValues(refPattern, refName).entrySet()) {
        final String groupName = patternGroup.getKey();
        final String value = Optional.ofNullable(patternGroup.getValue()).orElse("");

        final var placeholderKey = "ref." + groupName;
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
        placeholderMap.put(placeholderKey + ".slug", Lazy.by(() -> ProcessorUtil.slugify(value)));
      }
    }

    // dirty
    final Lazy<Boolean> dirty = Lazy.by(() -> !gitSituation.isClean());
    placeholderMap.put("dirty", Lazy.by(() -> dirty.get() ? "-DIRTY" : ""));
    placeholderMap.put("dirty.snapshot", Lazy.by(() -> dirty.get() ? "-SNAPSHOT" : ""));

    // describe
    final Lazy<GitDescription> description = Lazy.by(gitSituation::getDescription);
    placeholderMap.put("describe", Lazy.by(() -> description.get().toString()));
    final Lazy<String> descriptionTag = Lazy.by(() -> description.get().tag());
    placeholderMap.put("describe.tag", descriptionTag);

    final Lazy<Matcher> descriptionTagVersionMatcher =
        Lazy.by(() -> matchVersion(descriptionTag.get()));

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

    final Lazy<Integer> descriptionDistance = Lazy.by(() -> description.get().distance());
    placeholderMap.put(
        "describe.distance", Lazy.by(() -> String.valueOf(descriptionDistance.get())));
    placeholderMap.put(
        "describe.distance.snapshot",
        Lazy.by(() -> (descriptionDistance.get() == 0 ? "" : "-SNAPSHOT")));

    placeholderMap.put(
        "describe.tag.version.next",
        Lazy.by(
            () -> {
              String snapshot = placeholderMap.get("describe.distance.snapshot").get();
              return String.join(
                      ".",
                      placeholderMap.get("describe.tag.version.major").get(),
                      placeholderMap.get("describe.tag.version.minor").get(),
                      placeholderMap
                          .get("describe.tag.version.patch" + (snapshot.isEmpty() ? "" : ".next"))
                          .get())
                  + snapshot;
            }));

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

    // describe tag pattern groups
    final Lazy<Map<String, @Nullable String>> describeTagPatternValues =
        Lazy.by(
            () -> patternGroupValues(gitSituation.getDescribeTagPattern(), descriptionTag.get()));
    for (String groupName : patternGroups(gitSituation.getDescribeTagPattern())) {
      final var placeholderKey = "describe.tag." + groupName;
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
          Lazy.by(() -> nonNullOrEmpty(describeTagPatternValues.get().get(groupName)));
      placeholderMap.put(placeholderKey, groupValue);
      placeholderMap.put(
          placeholderKey + ".slug", Lazy.by(() -> ProcessorUtil.slugify(groupValue.get())));
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

  public static String nonNullOrEmpty(@Nullable String value) {
    return nonNullOrElse(value, "");
  }

  public static <T> T nonNullOrElse(@Nullable T value, T defaultValue) {
    return Objects.requireNonNullElse(value, defaultValue);
  }

  public static Map<String, Supplier<String>> nonNullOrEmpty(
      Map<String, Supplier<@Nullable String>> map) {
    return map.entrySet().stream()
        .collect(
            Collectors.toMap(
                Map.Entry::getKey, e -> () -> Optional.ofNullable(e.getValue().get()).orElse("")));
  }

  @NullUnmarked
  static Matcher matchVersion(String input) {
    Matcher matcher = VERSION_PATTERN.matcher(input);
    //noinspection ResultOfMethodCallIgnored
    matcher.find();

    return matcher;
  }

  @NullUnmarked
  static String inc(String number, long increment) {
    String sanitized = number.isEmpty() ? "0" : number;
    return String.format("%01d", Long.parseLong(sanitized) + increment);
  }

  @NullUnmarked
  static String nextVersion(Matcher m) {
    String label = m.group("label");
    if (label != null && !label.isEmpty()) {
      Matcher tail = LABEL_TRAILING_NUM.matcher(label);
      tail.matches();
      String coreAndBuild =
          m.group("core") + (m.group("build") != null ? "." + m.group("build") : "");
      return coreAndBuild + "-" + tail.group(1) + inc(tail.group(2), 1);
    }
    if (m.group("build") != null) {
      return m.group("major")
          + "."
          + m.group("minor")
          + "."
          + m.group("patch")
          + "."
          + inc(m.group("build"), 1);
    } else if (m.group("patch") != null) {
      return m.group("major") + "." + m.group("minor") + "." + inc(m.group("patch"), 1);
    } else if (m.group("minor") != null) {
      return m.group("major") + "." + inc(m.group("minor"), 1);
    } else {
      return inc(nonNullOrElse(m.group("major"), "0"), 1);
    }
  }
}
