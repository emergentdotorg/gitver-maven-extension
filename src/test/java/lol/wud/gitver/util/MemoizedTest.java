package lol.wud.gitver.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MemoizedTest {

  @Test
  void getValueBySupplier() {
    Memoized<String> memoizedValue = Memoized.by(() -> "foo");
    String value = memoizedValue.get();
    assertThat(value).isEqualTo("foo");
  }

  @Test
  void getValueBySupplierOnlyCallOnce() {
    AtomicInteger count = new AtomicInteger();
    Memoized<Integer> memoizedValue = Memoized.by(count::incrementAndGet);

    memoizedValue.get();
    memoizedValue.get();
    Integer value = memoizedValue.get();

    assertThat(value).isEqualTo(1);
    assertThat(count.get()).isEqualTo(1);
  }
}
