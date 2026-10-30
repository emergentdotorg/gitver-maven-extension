package org.emergent.gitver.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LazyTest {

  @Test
  void getValueBySupplier() {
    Lazy<String> lazyValue = Lazy.by(() -> "foo");
    String value = lazyValue.get();
    assertThat(value).isEqualTo("foo");
  }

  @Test
  void getValueBySupplierOnlyCallOnce() {
    AtomicInteger count = new AtomicInteger();
    Lazy<Integer> lazyValue = Lazy.by(count::incrementAndGet);

    lazyValue.get();
    lazyValue.get();
    Integer value = lazyValue.get();

    assertThat(value).isEqualTo(1);
    assertThat(count.get()).isEqualTo(1);
  }
}
