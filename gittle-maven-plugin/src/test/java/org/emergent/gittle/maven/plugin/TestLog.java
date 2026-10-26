package org.emergent.gittle.maven.plugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
class TestLog extends SilentLog {

  private static final String ls = System.lineSeparator();

  private final List<String> messages = new ArrayList<>();

  boolean infoEnabled = false;
  boolean warnEnabled = false;
  boolean errorEnabled = false;

  @Override
  public void info(CharSequence content) {
    messages.add(getMsg(content, null));
  }

  @Override
  public void info(Throwable throwable) {
    messages.add(getMsg(null, throwable));
  }

  @Override
  public void info(CharSequence message, Throwable throwable) {
    messages.add(getMsg(message, throwable));
  }

  @Override
  public void warn(CharSequence message) {
    messages.add(getMsg(message, null));
  }

  private static @NonNull String getMsg(@Nullable Object content, @Nullable Throwable throwable) {
    return Stream.of(Optional.ofNullable(content), getErrorMsg(throwable))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .map(String::valueOf)
        .collect(Collectors.joining(ls));
  }

  private static Optional<String> getErrorMsg(@Nullable Throwable throwable) {
    return Optional.ofNullable(throwable)
        .map(
            th -> {
              try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                  PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8)) {
                th.printStackTrace(ps);
                return baos.toString(StandardCharsets.UTF_8);
              } catch (IOException e) {
                log.warn("Error printing stack trace", e);
              }
              return null;
            })
        .filter(s -> !s.isEmpty());
  }
}
