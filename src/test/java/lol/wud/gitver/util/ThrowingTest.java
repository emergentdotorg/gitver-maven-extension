package lol.wud.gitver.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ThrowingTest {

  @Test
  void get_returnsValue() {
    assertThat(Throwing.get(() -> "value")).isEqualTo("value");
  }

  @Test
  void get_wrapsCheckedException() {
    IOException cause = new IOException("boom");
    assertThatThrownBy(
            () ->
                Throwing.get(
                    () -> {
                      throw cause;
                    }))
        .isInstanceOf(GitverException.class)
        .hasCause(cause);
  }

  @Test
  void get_passesUncheckedExceptionThrough() {
    IllegalStateException cause = new IllegalStateException("boom");
    assertThatThrownBy(
            () ->
                Throwing.get(
                    () -> {
                      throw cause;
                    }))
        .isSameAs(cause);
  }
}
