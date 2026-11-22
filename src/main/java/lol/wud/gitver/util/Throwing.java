package lol.wud.gitver.util;

import com.pivovarit.function.ThrowingSupplier;
import com.pivovarit.function.exception.WrappedException;

/**
 * Adapts code that throws checked exceptions to unchecked {@link
 * com.pivovarit.function.exception.WrappedException}s.
 */
public final class Throwing {

    private Throwing() {}

    public static <T, E extends Exception> T uncheck(ThrowingSupplier<T, E> supplier) {
        try {
            return supplier.uncheck().get();
        } catch (WrappedException ex) {
            throw unwrap(ex);
        }
    }

    private static RuntimeException unwrap(Exception e) {
        Throwable cause = e;
        while (cause instanceof WrappedException we && we.getCause() != null) {
            cause = we.getCause();
        }
        if (cause instanceof RuntimeException re) return re;
        return new GitverException(cause);
    }
}
