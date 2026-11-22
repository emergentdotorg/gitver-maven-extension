package lol.wud.gitver.util;

import org.jspecify.annotations.Nullable;

public class GitverException extends RuntimeException {

    public GitverException(String message) {
        super(message);
    }

    public GitverException(@Nullable Throwable cause) {
        super(cause);
    }

    public GitverException(String message, Throwable cause) {
        super(message, cause);
    }
}
