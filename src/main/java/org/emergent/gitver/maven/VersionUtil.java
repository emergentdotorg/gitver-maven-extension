package org.emergent.gitver.maven;

import static org.emergent.gitver.util.StringUtil.nonNullOrElse;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure functions for parsing, incrementing and normalizing version strings. */
final class VersionUtil {

  static final Pattern VERSION_PATTERN =
      Pattern.compile(
          ".*?(?<version>"
              + "(?<core>(?<major>\\d+)(?:\\.(?<minor>\\d+)(?:\\.(?<patch>\\d+))?)?)"
              + "(?:\\.(?<build>\\d+))?(?:-(?<label>.*))?"
              + ")|");

  private static final Pattern LABEL_TRAILING_NUM = Pattern.compile("^(.*?)(\\d*)$");

  private VersionUtil() {}

  static String slugify(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("/", "-");
  }

  static Matcher matchVersion(String input) {
    Matcher matcher = VERSION_PATTERN.matcher(input);
    //noinspection ResultOfMethodCallIgnored
    matcher.find();

    return matcher;
  }

  static String inc(String number, long increment) {
    String sanitized = number.isEmpty() ? "0" : number;
    return String.format("%01d", Long.parseLong(sanitized) + increment);
  }

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
      return String.join(
          ".", m.group("major"), m.group("minor"), m.group("patch"), inc(m.group("build"), 1));
    } else if (m.group("patch") != null) {
      return String.join(".", m.group("major"), m.group("minor"), inc(m.group("patch"), 1));
    } else if (m.group("minor") != null) {
      return String.join(".", m.group("major"), inc(m.group("minor"), 1));
    } else {
      return inc(nonNullOrElse(m.group("major"), "0"), 1);
    }
  }
}
