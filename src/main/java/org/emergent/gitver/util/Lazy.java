package org.emergent.gitver.util;

import com.pivovarit.function.ThrowingSupplier;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

@NullUnmarked
public class Lazy<T> implements Supplier<T>, Serializable {

  @Serial private static final long serialVersionUID = 0;

  @NonNull private final Supplier<T> delegate;
  private transient volatile boolean initialized;

  // "value" does not need to be volatile; visibility relies on volatile read of "initialized".
  @Nullable private transient T value;

  private Lazy(@NonNull Supplier<T> delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  public static <T> Lazy<T> of(T value) {
    return wrap(() -> value);
  }

  public static <T> Lazy<T> by(@NonNull ThrowingSupplier<T, Exception> delegate) {
    return wrap(Throwing.sneaky(delegate));
  }

  public static <T> Lazy<T> wrap(Supplier<T> delegate) {
    return (delegate instanceof Lazy<T> memoized) ? memoized : new Lazy<>(delegate);
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
    return value;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "(" + delegate + ")";
  }
}
