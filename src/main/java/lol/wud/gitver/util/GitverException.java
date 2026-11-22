package lol.wud.gitver.util;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;

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

    public static GitverException of(String format, Object... arguments) {
        FormattingTuple tp = MessageFormatter.arrayFormat(format, arguments);
        GitverException gte = new GitverException(tp.getMessage());
        Optional.ofNullable(tp.getThrowable()).ifPresent(gte::initCause);
        return gte;
    }
}
