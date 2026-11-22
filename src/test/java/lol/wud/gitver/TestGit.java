package lol.wud.gitver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Builds throwaway repositories with the {@code git} executable. */
public final class TestGit {

    public static final String MASTER = "master";

    private final Path directory;

    private TestGit(Path directory) {
        this.directory = directory;
    }

    public static TestGit init(Path directory) throws Exception {
        Files.createDirectories(directory);
        TestGit git = new TestGit(directory);
        git.run("init", "--initial-branch", MASTER);
        git.run("config", "user.name", "nobody");
        git.run("config", "user.email", "nobody@nowhere.com");
        git.run("config", "commit.gpgsign", "false");
        git.run("config", "tag.gpgsign", "false");
        return git;
    }

    /** Wraps an existing repository. */
    public static TestGit open(Path directory) {
        return new TestGit(directory);
    }

    public void addAll() throws Exception {
        run("add", ".");
    }

    /** Commits whatever is staged, even nothing, and returns the new commit hash. */
    public String commit(String message) throws Exception {
        run("commit", "--allow-empty", "--message", message);
        return run("rev-parse", "HEAD");
    }

    public void tag(String name) throws Exception {
        run("tag", name);
    }

    private String run(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
        }
        return output;
    }
}
