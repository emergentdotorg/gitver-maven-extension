package lol.wud.gitver.git;

import java.nio.file.Path;
import java.util.List;
import lol.wud.gitver.util.GitverException;

/** The outcome of one git invocation, with output trimmed. */
public record Result(List<String> command, Path directory, int exitcode, String out, String err) {

    public Result {
        command = List.copyOf(command);
        out = out.trim();
        err = err.trim();
    }

    boolean ok() {
        return exitcode == 0;
    }

    public void assertNormalExitValue() {
        if (!ok()) {
            throw GitverException.of(
                    "Command `{}` failed in {} (exit {}): {}", String.join(" ", command), directory, exitcode, err);
        }
    }
}
