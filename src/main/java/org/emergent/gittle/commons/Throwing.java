package org.emergent.gittle.commons;

import com.pivovarit.function.ThrowingConsumer;
import com.pivovarit.function.ThrowingFunction;
import com.pivovarit.function.ThrowingRunnable;
import com.pivovarit.function.ThrowingSupplier;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class Throwing {

  public static <E extends Exception> void run(ThrowingRunnable<E> runnable) {
    ThrowingRunnable.unchecked(runnable).run();
  }

  public static <T, E extends Exception> T get(ThrowingSupplier<T, E> supplier) {
    return ThrowingSupplier.unchecked(supplier).get();
  }

  public static <T, E extends Exception> Optional<T> maybe(ThrowingSupplier<T, E> supplier) {
    try {
      return Optional.ofNullable(get(supplier));
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  public static <T, E extends Exception> Supplier<T> wrap(ThrowingSupplier<T, E> supplier) {
    return ThrowingSupplier.unchecked(supplier);
  }

  public static <T, E extends Exception> Consumer<T> wrap(ThrowingConsumer<T, E> consumer) {
    return ThrowingConsumer.unchecked(consumer);
  }

  public static <T, R, E extends Exception> Function<T, R> wrap(
      ThrowingFunction<T, R, E> function) {
    return ThrowingFunction.unchecked(function);
  }

  public static <T, R, E extends Exception> Function<T, Optional<R>> maybe(
      ThrowingFunction<T, R, E> function) {
    return t -> {
      R result = null;
      try {
        result = function.apply(t);
      } catch (Exception ignored) {
      }
      return Optional.ofNullable(result);
    };
  }

  public static <R> R tryUnchecked(Callable<R> block) {
    try {
      return block.call();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
