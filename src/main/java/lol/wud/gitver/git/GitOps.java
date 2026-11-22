package lol.wud.gitver.git;

import static lol.wud.gitver.git.GitDescription.NO_COMMIT;
import static lol.wud.gitver.git.GitDescription.ROOT_TAG;
import static lol.wud.gitver.util.StringUtil.isEmpty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lol.wud.gitver.util.GitverException;
import lol.wud.gitver.util.StringUtil;
import lombok.SneakyThrows;
import org.jspecify.annotations.Nullable;

class GitOps {

    // <tag>-<distance>-g<abbreviated hash>, as printed by `git describe --long`
    private static final Pattern LONG_DESCRIPTION = Pattern.compile("(.+)-(\\d+)-g([0-9a-f]+)((?:-dirty)?)");
    private static final int VERSION_ABBR_LENGTH = 10;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final Path directory;
    private final Duration timeout;
    private final String prefix;

    private final Function<String, @Nullable String> env;

    public GitOps() {
        this(Path.of("."));
    }

    public GitOps(Path directory) {
        this(directory, System::getenv);
    }

    public GitOps(Path directory, Function<String, @Nullable String> env) {
        this("", directory, env);
    }

    public GitOps(String prefix, Path directory, Function<String, @Nullable String> env) {
        this.env = env;
        this.directory = directory;
        this.prefix = prefix;
        this.timeout = DEFAULT_TIMEOUT;
    }

    public Path getRootDirectory() {
        return directory;
    }

    public String version() {
        return getGitVersionOverride().orElseGet(() -> description() + (isClean() ? "" : ".dirty"));
    }

    @SuppressWarnings({"DataFlowIssue", "NullAway"})
    public String originUrl() {
        String result = invoke("config", "remote.origin.url");
        return Optional.ofNullable(result).filter(StringUtil::isNotEmpty).orElse(null);
    }

    @SuppressWarnings({"DataFlowIssue", "NullAway"})
    public String branchName() {
        Optional<String> result = Optional.ofNullable(invoke("branch", "--show-current"));
        return result.filter(StringUtil::isNotEmpty).orElse(null);
    }

    public String fullGitHash() {
        return invoke("rev-parse", "HEAD");
    }

    public String abbreviatedGitHash() {
        return fullGitHash().substring(0, VERSION_ABBR_LENGTH);
    }

    public boolean isCleanTag() {
        return isClean() && description().isPlainTag();
    }

    public Integer commitDistance() {
        return description().distance();
    }

    @SuppressWarnings({"NullAway"})
    public String lastTag() {
        return description().tag();
    }

    public boolean isClean() {
        return invoke("status", "--porcelain").isEmpty();
    }

    public ZonedDateTime authorTimestamp() {
        String ts = invoke("show", "-s", "--format=%aI", "HEAD");
        return ZonedDateTime.parse(ts);
    }

    public ZonedDateTime commitTimestamp() {
        String ts = invoke("show", "-s", "--format=%cI", "HEAD");
        return ZonedDateTime.parse(ts);
    }

    public GitDescription description() {
        String tagOverride = getGitTagOverride().orElse(null);
        if (tagOverride != null) {
            String commit = revParseHeadCommit();
            return new GitDescription(commit, tagOverride, 0);
        }

        Result result = invokeWithResult(Stream.of(
                        "describe",
                        "--tags",
                        "--long",
                        "--first-parent",
                        "--abbrev=7",
                        isEmpty(prefix) ? null : "--match=%s*".formatted(prefix),
                        "HEAD")
                .filter(Objects::nonNull)
                .toArray(String[]::new));

        if (result.ok()) {
            String stdout = result.out();
            if (StringUtil.isNotEmpty(prefix)) {
                stdout = stdout.replaceFirst("^%s".formatted(prefix), "");
            }
            Matcher match = LONG_DESCRIPTION.matcher(stdout);
            if (match.matches()) {
                String tag = match.group(1);
                int distance = Integer.parseInt(match.group(2));
                String commit = match.group(3);
                return new GitDescription(commit, tag, distance);
            } else {
                return new GitDescription(stdout, ROOT_TAG, 0);
                // throw new GitverException("Unexpected `git describe` output: '%s'".formatted(stdout));
            }
        }

        String stderr = result.err();
        if (stderr.contains("Not a valid object name HEAD")) {
            // No commits yet.
            return new GitDescription(NO_COMMIT, ROOT_TAG, 0);
        } else if (stderr.contains("No names found") || stderr.contains("No tags can describe")) {
            // No tag is reachable: count the commits instead.
            String commit = revParseHeadCommit();
            int distance = revListHeadCount();
            return new GitDescription(commit, ROOT_TAG, distance);
        }

        throw new GitverException("`git describe` failed in %s: %s".formatted(getRootDirectory(), stderr));
    }

    private String revParseHeadCommit() {
        Result head = invokeWithResult("rev-parse", "--verify", "--quiet", "HEAD");
        return head.ok() ? head.out() : NO_COMMIT;
    }

    private int revListHeadCount() {
        Result head = invokeWithResult("rev-list", "--count", "--first-parent", "HEAD");
        return head.ok() ? Integer.parseInt(head.out()) : 0;
    }

    private Optional<String> getGitTagOverride() {
        return envVarOrFromTestingProperty("GIT_TAG");
    }

    protected Optional<String> getGitVersionOverride() {
        return envVarOrFromTestingProperty("GIT_VERSION");
    }

    private Optional<String> envVarOrFromTestingProperty(String name) {
        return Optional.ofNullable(env.apply(name)).map(StringUtil::emptyToNull);
    }

    /**
     * Runs git and returns its trimmed output.
     *
     * @throws GitverException if git fails
     */
    public String invoke(String... args) {
        Result result = invokeWithResult(args);
        result.assertNormalExitValue();
        return result.out();
    }

    @SneakyThrows
    public Result invokeWithResult(String... args) {
        List<String> command = Arrays.stream(args).collect(Collectors.toList());
        command.add(0, "git");

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile());
        // Output is matched against English messages and must not wait on a lock or a prompt.
        builder.environment().put("LC_ALL", "C");
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new GitverException("Unable to run `%s` in %s: %s".formatted(describe(command), directory, e), e);
        }

        // Drain both streams concurrently so a full pipe can't stall the process.
        CompletableFuture<String> out = read(process.getInputStream());
        CompletableFuture<String> err = read(process.getErrorStream());
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new GitverException(
                        "`%s` timed out after %s in %s".formatted(describe(command), timeout, directory));
            }
            return new Result(command, directory, process.exitValue(), out.get(), err.get());
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new GitverException("Interrupted while running `%s`".formatted(describe(command)), e);
        } catch (Exception e) {
            process.destroyForcibly();
            throw new GitverException("Failed running `%s` in %s".formatted(describe(command), directory), e);
        }
    }

    private static CompletableFuture<String> read(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new GitverException("Unable to read git output", e);
            }
        });
    }

    private static String describe(List<String> command) {
        return String.join(" ", command);
    }
}
