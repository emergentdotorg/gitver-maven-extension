package lol.wud.gitver.git;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lol.wud.gitver.util.GitverException;

/** Runs the {@code git} executable in a directory. */
final class Git {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** The outcome of one git invocation, with output trimmed. */
    public record Result(int exitCode, String out, String err) {

        boolean ok() {
            return exitCode == 0;
        }
    }

    private final Path directory;

    Git(Path directory) {
        this.directory = directory;
    }

    /**
     * Runs git and returns its result whatever the exit code.
     *
     * @throws GitverException if git can't be started or doesn't finish in time
     */
    Result run(String... args) {
        List<String> command = new ArrayList<>(args.length + 1);
        command.add("git");
        command.addAll(List.of(args));

        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
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
            if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new GitverException(
                        "`%s` timed out after %s in %s".formatted(describe(command), TIMEOUT, directory));
            }
            return new Result(process.exitValue(), out.get().trim(), err.get().trim());
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new GitverException("Interrupted while running `%s`".formatted(describe(command)), e);
        } catch (Exception e) {
            process.destroyForcibly();
            throw new GitverException("Failed running `%s` in %s".formatted(describe(command), directory), e);
        }
    }

    /**
     * Runs git and returns its trimmed output.
     *
     * @throws GitverException if git fails
     */
    String output(String... args) {
        Result result = run(args);
        if (!result.ok()) {
            throw new GitverException("`git %s` failed in %s (exit %d): %s"
                    .formatted(String.join(" ", args), directory, result.exitCode(), result.err()));
        }
        return result.out();
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
