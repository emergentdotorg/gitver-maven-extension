package lol.wud.gitver.git;

public record GitDescription(String commit, String tag, int distance, boolean dirty) {

    public GitDescription(String commit, String tag, int distance) {
        this(commit, tag, distance, false);
    }

    @Override
    public String toString() {
        String gstr = commit.substring(0, Math.min(7, commit.length()));
        return tag + "-" + distance + "-g" + gstr + (dirty ? "-dirty" : "");
    }
}
