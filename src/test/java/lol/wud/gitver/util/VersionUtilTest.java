package lol.wud.gitver.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;

class VersionUtilTest {

    @Test
    void testPatternNewlines() {
        String pattern = VersionUtil.VERSION_PATTERN.pattern();
        assertThat(pattern).isEqualTo(pattern.replaceAll("[\n\r]", ""));
    }

    @Test
    void versionPattern_matchesFourSegmentTag() {
        Matcher m = match("1.0.0.3");
        assertThat(m.group("version")).isEqualTo("1.0.0.3");
        assertThat(m.group("core")).isEqualTo("1.0.0");
        assertThat(m.group("major")).isEqualTo("1");
        assertThat(m.group("minor")).isEqualTo("0");
        assertThat(m.group("patch")).isEqualTo("0");
        assertThat(m.group("build")).isEqualTo("3");
        assertThat(m.group("label")).isNull();
    }

    @Test
    void versionPattern_matchesThreeSegmentTag_buildIsNull() {
        Matcher m = match("1.0.0");
        assertThat(m.group("version")).isEqualTo("1.0.0");
        assertThat(m.group("core")).isEqualTo("1.0.0");
        assertThat(m.group("build")).isNull();
        assertThat(m.group("label")).isNull();
    }

    @Test
    void versionPattern_matchesFourSegmentWithLabel() {
        Matcher m = match("1.2.3.4-RC1");
        assertThat(m.group("version")).isEqualTo("1.2.3.4-RC1");
        assertThat(m.group("core")).isEqualTo("1.2.3");
        assertThat(m.group("build")).isEqualTo("4");
        assertThat(m.group("label")).isEqualTo("RC1");
    }

    @Test
    void versionPattern_matchesThreeSegmentWithLabel_buildStillNull() {
        Matcher m = match("1.2.3-alpha");
        assertThat(m.group("version")).isEqualTo("1.2.3-alpha");
        assertThat(m.group("core")).isEqualTo("1.2.3");
        assertThat(m.group("build")).isNull();
        assertThat(m.group("label")).isEqualTo("alpha");
    }

    private static Matcher match(String input) {
        Matcher m = VersionUtil.VERSION_PATTERN.matcher(input);
        m.find();
        return m;
    }
}
