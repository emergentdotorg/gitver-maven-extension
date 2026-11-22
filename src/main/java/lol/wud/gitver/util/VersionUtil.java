package lol.wud.gitver.util;

import static java.time.format.DateTimeFormatter.ISO_INSTANT;

import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure functions for parsing, incrementing and normalizing version strings. */
public class VersionUtil {

    static final Pattern VERSION_PATTERN = Pattern.compile("""
        .*?(?<version>\
        (?<core>(?<major>\\d+)(?:\\.(?<minor>\\d+)(?:\\.(?<patch>\\d+))?)?)\
        (?:\\.(?<build>\\d+))?(?:-(?<label>.*))?\
        )|\
        """);

    private VersionUtil() {}

    public static String slugify(String value) {
        return value.replace("/", "-");
    }

    static Matcher matchVersion(String input) {
        Matcher matcher = VERSION_PATTERN.matcher(input);
        //noinspection ResultOfMethodCallIgnored
        matcher.find();

        return matcher;
    }

    static String inc(String number) {
        String sanitized = number.isEmpty() ? "0" : number;
        return String.format("%01d", Long.parseLong(sanitized) + 1);
    }

    /** Formats <code>time</code> as an ISO instant, or a fixed placeholder for an unknown time. */
    public static String isoTimestamp(ZonedDateTime time) {
        return time.toEpochSecond() > 0 ? time.format(ISO_INSTANT) : "0000-01-01T00:00:00Z";
    }
}
