package lol.wud.gitver.git;

public record GitDescription(String commit, String tag, int distance) {

    /** The commit of a repository that has none yet. */
    public static final String NO_COMMIT = "0".repeat(40);

    public static final String ROOT_TAG = "root";

    @Override
    public String toString() {
        return tag + "-" + distance + "-g" + shortCommit();
    }

    public boolean isPlainTag() {
        // return !GIT_TAG_PATTERN.asMatchPredicate().test(description);
        return ROOT_TAG.equals(tag);
    }

    public String shortCommit() {
        return shorten(commit);
    }

    private static String shorten(String commit) {
        return commit.substring(0, Math.min(7, commit.length()));
    }
}
