package org.emergent.gitver.util;

import com.pivovarit.function.ThrowingSupplier;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

public class MemoizedNullable<T> implements Supplier<@Nullable T>, Serializable {

  @Serial private static final long serialVersionUID = 0;

  private final Supplier<@Nullable T> delegate;
  private transient volatile boolean initialized;

  // "value" does not need to be volatile; visibility relies on volatile read of "initialized".
  @Nullable private transient T value;

  private MemoizedNullable(Supplier<@Nullable T> delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  public static <T> MemoizedNullable<T> of(@Nullable T value) {
    return by(() -> value);
  }

  public static <T> MemoizedNullable<T> by(Supplier<@Nullable T> delegate) {
    return (delegate instanceof MemoizedNullable<T> memo) ? memo : new MemoizedNullable<>(delegate);
  }

  public static <T> MemoizedNullable<T> byThrowing(
      ThrowingSupplier<@Nullable T, Exception> delegate) {
    return by(ThrowingSupplier.sneaky(delegate));
  }

  @Override
  public @Nullable T get() {
    // A 2-field variant of double-checked locking.
    if (!initialized) {
      synchronized (this) {
        if (!initialized) {
          T t = delegate.get();
          value = t;
          initialized = true;
          return t;
        }
      }
    }
    return value;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "(" + delegate + ")";
  }
}
