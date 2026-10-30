package org.emergent.gitver.util;

import com.pivovarit.function.ThrowingFunction;
import com.pivovarit.function.ThrowingSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

public final class Throwing {

  private Throwing() {}

  public static <T, E extends Exception> T get(ThrowingSupplier<T, E> supplier) {
    return ThrowingSupplier.unchecked(supplier).get();
  }

  public static <T, E extends Exception> Supplier<T> wrap(ThrowingSupplier<T, E> supplier) {
    return ThrowingSupplier.unchecked(supplier);
  }

  public static <T, E extends Exception> Supplier<T> sneaky(ThrowingSupplier<T, E> supplier) {
    return ThrowingSupplier.sneaky(supplier);
  }

  public static <T, R, E extends Exception> Function<T, R> sneaky(
      ThrowingFunction<T, R, E> function) {
    return ThrowingFunction.sneaky(function);
  }
}
