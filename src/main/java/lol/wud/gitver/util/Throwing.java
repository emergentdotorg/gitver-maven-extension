package lol.wud.gitver.util;

import com.pivovarit.function.ThrowingSupplier;
import java.util.function.Supplier;

/** Adapts code that throws checked exceptions to unchecked {@link GitverException}s. */
public final class Throwing {

  private Throwing() {}

  public static <T, E extends Exception> T get(ThrowingSupplier<T, E> supplier) {
    try {
      return supplier.get();
    } catch (Exception e) {
      throw GitverException.wrap(e);
    }
  }

  public static <T, E extends Exception> Supplier<T> wrap(ThrowingSupplier<T, E> supplier) {
    return () -> get(supplier);
  }
}
