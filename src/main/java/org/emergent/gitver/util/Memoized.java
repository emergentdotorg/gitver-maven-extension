package org.emergent.gitver.util;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class Memoized<T> implements Supplier<T>, Serializable {

  @Serial private static final long serialVersionUID = 0;

  private final Supplier<T> delegate;
  private transient volatile boolean initialized;

  // "value" does not need to be volatile; visibility relies on volatile read of "initialized".
  @Nullable private transient T value;

  private Memoized(Supplier<T> delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  public static <T> Memoized<T> of(T value) {
    return by(() -> value);
  }

  public static <T> Memoized<T> by(Supplier<T> delegate) {
    return (delegate instanceof Memoized<T> memo) ? memo : new Memoized<>(delegate);
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
