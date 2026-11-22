package lol.wud.gitver.util;

import static lol.wud.gitver.util.StringUtil.nonNullOrElse;

import java.util.regex.Matcher;
import lol.wud.gitver.git.GitDescription;
import lol.wud.gitver.git.GitSituation;

/**
 * Calculates the version that replaces a project version: the patch-incremented
 * <code>major.minor.patch</code> of the most recent tag, with <code>-SNAPSHOT</code> appended when
 * the work tree is dirty or past the tag. Without a tag, the project version is kept.
 */
public class VersionFormatter {

    private final GitSituation situation;

    public VersionFormatter(GitSituation situation) {
        this.situation = situation;
    }

    /** The version that replaces <code>projectVersion</code>. */
    public String formatVersion(String projectVersion) {
        GitDescription description = situation.getDescription();
        if ("root".equals(description.tag())) {
            return VersionUtil.slugify(projectVersion);
        }
        boolean snapshot = !situation.isClean() || description.distance() != 0;
        Matcher tag = VersionUtil.matchVersion(description.tag());
        String major = nonNullOrElse(tag.group("major"), "0");
        String minor = nonNullOrElse(tag.group("minor"), "0");
        String patch = nonNullOrElse(tag.group("patch"), "0");
        String version = major + "." + minor + "." + (snapshot ? VersionUtil.inc(patch) : patch);
        return VersionUtil.slugify(snapshot ? version + "-SNAPSHOT" : version);
    }
}
