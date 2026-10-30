package org.emergent.gitver.util;

import com.pivovarit.function.ThrowingSupplier;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class Lazy<T> implements Supplier<T>, Serializable {

  @Serial private static final long serialVersionUID = 0;

  private final Supplier<T> delegate;
  private transient volatile boolean initialized;

  // "value" does not need to be volatile; visibility relies on volatile read of "initialized".
  @Nullable private transient T value;

  private Lazy(Supplier<T> delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  public static <T> Lazy<T> of(T value) {
    return by(() -> value);
  }

  public static <T> Lazy<T> by(Supplier<T> delegate) {
    return (delegate instanceof Lazy<T> memo) ? memo : new Lazy<>(delegate);
  }

  public static <T> NullableMemo<T> byNullable(Supplier<@Nullable T> delegate) {
    return NullableMemo.by(delegate);
  }

  public static <T> Lazy<T> byThrowing(ThrowingSupplier<T, Exception> delegate) {
    return by(Throwing.sneaky(delegate));
  }

  @Override
  public T get() {
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
    return Objects.requireNonNull(value);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "(" + delegate + ")";
  }
}
