package lol.wud.gitver.util;

import java.util.Objects;
import org.apache.maven.shared.utils.StringUtils;
import org.jspecify.annotations.Nullable;

public final class StringUtil {

    private StringUtil() {}


    /**
     * Returns the given string if it is non-null; the empty string otherwise.
     *
     * @param string the string to test and possibly return
     * @return {@code string} itself if it is non-null; {@code ""} if it is null
     */
    public static String nullToEmpty(@Nullable String string) {
        return (string == null) ? "" : string;
    }

    /**
     * Returns the given string if it is nonempty; {@code null} otherwise.
     *
     * @param string the string to test and possibly return
     * @return {@code string} itself if it is nonempty; {@code null} if it is empty or null
     */
    public static @Nullable String emptyToNull(@Nullable String string) {
        return isEmpty(string) ? null : string;
    }

    public static @Nullable String trim(@Nullable String value) {
        return StringUtils.trim(value);
    }

    public static String rightPad(String str, int size) {
        return rightPad(str, size, " ");
    }

    public static String rightPad(String str, int size, String delim) {
        return StringUtils.rightPad(str, size, delim);
    }

    public static String leftPad(String str, int size) {
        return leftPad(str, size, " ");
    }

    public static String leftPad(String str, int size, String delim) {
        return StringUtils.leftPad(str, size, delim);
    }

    public static String repeat(String str, int repeat) {
        return StringUtils.repeat(str, repeat);
    }

    public static String nonNullOrEmpty(@Nullable String value) {
        return nonNullOrElse(value, "");
    }

    public static <T> T nonNullOrElse(@Nullable T value, T defaultValue) {
        return Objects.requireNonNullElse(value, defaultValue);
    }

    public static boolean isBlank(@Nullable String s) {
        return StringUtils.isBlank(s);
    }

    public static boolean isNotBlank(@Nullable String s) {
        return StringUtils.isNotBlank(s);
    }

    public static boolean isEmpty(@Nullable String s) {
        return StringUtils.isEmpty(s);
    }

    public static boolean isNotEmpty(@Nullable String s) {
        return StringUtils.isNotEmpty(s);
    }
}
