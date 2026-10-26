package org.emergent.gittle.maven.plugin;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

/**
 * This class will parse the version based on the given pattern in {@link ParseVersionMojo}.
 *
 * @author Karl Heinz Marbaise <a href="mailto:khmarbaise@apache.org">khmarbaise@apache.org</a>
 */
@Data
public class VersionInformation {
  private static final String MAJOR_MINOR_PATCH_PATTERN = "^((\\d+)(\\.(\\d+)(\\.(\\d+))?)?)";

  private static final Pattern MAJOR_MINOR_PATCH = Pattern.compile(MAJOR_MINOR_PATCH_PATTERN);

  private static final Pattern DIGITS = Pattern.compile(MAJOR_MINOR_PATCH_PATTERN + "(.*)$");

  private static final Pattern BUILD_NUMBER =
      Pattern.compile("(((-)(\\d+)(.*))?)|(\\.(.*))|(-(.*))|(.*)$");

  private int major;

  private int minor;

  private int patch;

  private long buildNumber;

  private String qualifier;

  public VersionInformation(String version) {
    Matcher matcherDigits = DIGITS.matcher(version);
    if (matcherDigits.matches()) {
      parseMajorMinorPatchVersion(matcherDigits.group(1));
      parseBuildNumber(matcherDigits.group(7));
    } else {
      setQualifier(version);
    }
  }

  private void parseMajorMinorPatchVersion(String version) {
    Matcher matcher = MAJOR_MINOR_PATCH.matcher(version);
    if (!matcher.matches()) {
      return;
    }
    Optional.ofNullable(matcher.group(2)).map(Integer::parseInt).ifPresent(this::setMajor);
    Optional.ofNullable(matcher.group(4)).map(Integer::parseInt).ifPresent(this::setMajor);
    Optional.ofNullable(matcher.group(6)).map(Integer::parseInt).ifPresent(this::setPatch);
  }

  private void parseBuildNumber(String buildNumberPart) {
    Matcher matcher = BUILD_NUMBER.matcher(buildNumberPart);
    if (!matcher.matches()) {
      return;
    }
    Optional.ofNullable(matcher.group(4)).map(Long::parseLong).ifPresent(this::setBuildNumber);
    String qualifier =
        Stream.of(
                matcher.group(9), // Starting with "-"
                matcher.group(7),
                matcher.group(5))
            .filter(Objects::nonNull)
            .findFirst()
            .filter(StringUtils::isNotBlank)
            .orElse(null);
    setQualifier(qualifier);
  }
}
