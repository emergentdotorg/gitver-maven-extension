package org.emergent.gitver.util;

import com.pivovarit.function.ThrowingSupplier;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

@NullUnmarked
public class NullableMemo<T> implements Supplier<@Nullable T>, Serializable {

  @Serial private static final long serialVersionUID = 0;

  private final Supplier<@Nullable T> delegate;
  private transient volatile boolean initialized;

  // "value" does not need to be volatile; visibility relies on volatile read of "initialized".
  @Nullable private transient T value;

  private NullableMemo(Supplier<@Nullable T> delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  public static <T> NullableMemo<T> of(@Nullable T value) {
    return by(() -> value);
  }

  public static <T> NullableMemo<T> by(Supplier<@Nullable T> delegate) {
    return (delegate instanceof NullableMemo<T> memo) ? memo : new NullableMemo<>(delegate);
  }

  public static <T> NullableMemo<T> byThrowing(ThrowingSupplier<T, Exception> delegate) {
    return by(Throwing.sneaky(delegate));
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
