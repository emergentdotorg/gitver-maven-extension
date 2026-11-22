package lol.wud.gitver.git;

import java.io.File;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lol.wud.gitver.util.GitverException;
import lol.wud.gitver.util.Memoized;
import org.jspecify.annotations.Nullable;

final class ExecGitSituation implements GitSituation {

    /** The commit of a repository that has none yet. */
    static final String NO_COMMIT = "0".repeat(40);

    static final String ROOT_TAG = "root";

    // <tag>-<distance>-g<abbreviated hash>, as printed by `git describe --long`
    private static final Pattern LONG_DESCRIPTION = Pattern.compile("(.+)-(\\d+)-g([0-9a-f]+)(-dirty)?");

    private final Git git;
    private final File rootDirectory;
    private final @Nullable String tagOverride;
    private final Supplier<Boolean> clean = Memoized.by(this::readClean);
    private final Supplier<GitDescription> description = Memoized.by(this::readDescription);

    ExecGitSituation(Git git, File rootDirectory, @Nullable String tagOverride) {
        this.git = git;
        this.rootDirectory = rootDirectory;
        this.tagOverride = tagOverride == null || tagOverride.isBlank() ? null : tagOverride.trim();
    }

    @Override
    public boolean isClean() {
        return clean.get();
    }

    @Override
    public GitDescription getDescription() {
        return description.get();
    }

    @Override
    public File getRootDirectory() {
        return rootDirectory;
    }

    @Override
    public String getRev() {
        return getDescription().commit();
    }

    @Override
    public String getRefName() {
        GitDescription desc = getDescription();
        String tag = desc.tag();
        return !"root".equals(tag) ? tag : desc.commit();
    }

    private boolean readClean() {
        // Untracked files count as changes.
        return git.output("status", "--porcelain").isEmpty();
    }

    private GitDescription readDescription() {
        Git.Result head = git.run("rev-parse", "--verify", "--quiet", "HEAD");
        if (!head.ok()) {
            // No commits yet.
            return new GitDescription(NO_COMMIT, tagOverride != null ? tagOverride : ROOT_TAG, 0);
        }
        String commit = head.out();
        if (tagOverride != null) {
            return new GitDescription(commit, tagOverride, 0);
        }

        String prefixValue = "";
        Git.Result described = git.run("describe", "--tags", "--long", "--first-parent", "--abbrev=7",
                "--match=%s*".formatted(prefixValue), "HEAD");
        if (described.ok()) {
            Matcher match = LONG_DESCRIPTION.matcher(described.out());
            if (!match.matches()) {
                throw new GitverException("Unexpected `git describe` output: '%s'".formatted(described.out()));
            }
            boolean dirty = match.groupCount() >= 4 && "-dirty".equals(match.group(4));
            return new GitDescription(commit, match.group(1), Integer.parseInt(match.group(2)), dirty);
        }
        if (!described.err().contains("No names found") && !described.err().contains("No tags can describe")) {
            throw new GitverException("`git describe` failed in %s: %s".formatted(rootDirectory, described.err()));
        }

        // No tag is reachable: count the commits instead.
        int distance = Integer.parseInt(git.output("rev-list", "--count", "--first-parent", "HEAD"));
        return new GitDescription(commit, ROOT_TAG, distance);
    }
}
