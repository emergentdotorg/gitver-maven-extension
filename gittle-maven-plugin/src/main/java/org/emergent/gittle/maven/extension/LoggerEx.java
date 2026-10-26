package org.emergent.gittle.maven.extension;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.codehaus.plexus.logging.Logger;
import org.jspecify.annotations.Nullable;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MarkerIgnoringBase;
import org.slf4j.helpers.MessageFormatter;

public interface LoggerEx extends org.slf4j.Logger, org.codehaus.plexus.logging.Logger {

  int LEVEL_DEBUG = 0;

  int LEVEL_INFO = 1;

  int LEVEL_WARN = 2;

  int LEVEL_ERROR = 3;

  int LEVEL_FATAL = 4;

  int LEVEL_DISABLED = 5;

  public static Slf4jImpl wrap(org.slf4j.Logger logger) {
    return new Slf4jImpl(logger);
  }

  public static PlexusImpl wrap(org.codehaus.plexus.logging.Logger logger) {
    return new PlexusImpl(logger);
  }

  abstract class AbstractImpl extends MarkerIgnoringBase implements LoggerEx {

    public static final int LEVEL_TRACE = -1;

    @Getter private final String name;

    @Getter
    @Setter
    @Accessors(fluent = false, chain = false)
    private int threshold = LoggerEx.LEVEL_DEBUG;

    public AbstractImpl() {
      this("");
    }

    public AbstractImpl(String name) {
      this.name = name;
    }

    @Override
    public boolean isTraceEnabled() {
      return isEnabled(LEVEL_TRACE);
    }

    @Override
    public void trace(String msg) {
      logit(LEVEL_TRACE, msg);
    }

    @Override
    public void trace(String format, Object arg) {
      logit(LEVEL_TRACE, format, arg);
    }

    @Override
    public void trace(String format, Object arg1, Object arg2) {
      logit(LEVEL_TRACE, format, arg1, arg2);
    }

    @Override
    public void trace(String format, Object... arguments) {
      logit(LEVEL_TRACE, format, arguments);
    }

    @Override
    public void trace(String msg, Throwable t) {
      logit(LEVEL_TRACE, msg, t);
    }

    @Override
    public void debug(String format, Object arg) {
      logit(LoggerEx.LEVEL_DEBUG, format, arg);
    }

    @Override
    public void debug(String format, Object arg1, Object arg2) {
      logit(LoggerEx.LEVEL_DEBUG, format, arg1, arg2);
    }

    @Override
    public void info(String format, Object arg) {
      logit(LoggerEx.LEVEL_INFO, format, arg);
    }

    @Override
    public void info(String format, Object arg1, Object arg2) {
      logit(LoggerEx.LEVEL_INFO, format, arg1, arg2);
    }

    @Override
    public void warn(String format, Object arg) {
      logit(LoggerEx.LEVEL_WARN, format, arg);
    }

    @Override
    public void warn(String format, Object arg1, Object arg2) {
      logit(LoggerEx.LEVEL_WARN, format, arg1, arg2);
    }

    public void debug(String message) {
      logit(LEVEL_DEBUG, message);
    }

    public void debug(String message, Throwable throwable) {
      logit(LEVEL_DEBUG, message, throwable);
    }

    public void debug(String message, Object... arguments) {
      logit(LEVEL_DEBUG, message, arguments);
    }

    public boolean isDebugEnabled() {
      return isEnabled(LEVEL_DEBUG);
    }

    public void info(String message) {
      logit(LEVEL_INFO, message);
    }

    public void info(String message, Throwable throwable) {
      logit(LEVEL_INFO, message, throwable);
    }

    public void info(String message, Object... arguments) {
      logit(LEVEL_INFO, message, arguments);
    }

    public boolean isInfoEnabled() {
      return isEnabled(LEVEL_INFO);
    }

    public void warn(String message) {
      logit(LEVEL_WARN, message, message);
    }

    public void warn(String message, Throwable throwable) {
      logit(LEVEL_WARN, message, message, throwable);
    }

    public void warn(String message, Object... arguments) {
      logit(LEVEL_WARN, message, arguments);
    }

    public boolean isWarnEnabled() {
      return isEnabled(LEVEL_WARN);
    }

    public void error(String message) {
      logit(LEVEL_ERROR, message, message);
    }

    @Override
    public void error(String format, Object arg) {
      logit(LoggerEx.LEVEL_ERROR, format, arg);
    }

    @Override
    public void error(String format, Object arg1, Object arg2) {
      logit(LoggerEx.LEVEL_ERROR, format, arg1, arg2);
    }

    public void error(String message, Throwable throwable) {
      logit(LEVEL_ERROR, message, message, throwable);
    }

    public void error(String message, Object... arguments) {
      logit(LEVEL_ERROR, message, arguments);
    }

    public boolean isErrorEnabled() {
      return isEnabled(LEVEL_ERROR);
    }

    public void fatalError(String message) {
      logit(LEVEL_FATAL, message, message);
    }

    public void fatalError(String message, Throwable throwable) {
      logit(LEVEL_FATAL, message, message, throwable);
    }

    public void fatalError(String message, Object... arguments) {
      logit(LEVEL_FATAL, message, arguments);
    }

    public boolean isFatalErrorEnabled() {
      return isEnabled(LEVEL_FATAL);
    }

    @Override
    public @Nullable Logger getChildLogger(String name) {
      return null;
    }

    protected boolean isEnabled(int level) {
      return getThreshold() <= level;
    }

    protected abstract void logit(int level, CharSequence format, Object... arguments);
  }

  class SilentImpl extends AbstractImpl implements LoggerEx {

    public static final SilentImpl INSTANCE = new SilentImpl();

    protected void logit(int level, CharSequence format, Object... arguments) {}
  }

  class MemImpl extends AbstractImpl implements LoggerEx, org.apache.maven.plugin.logging.Log
  // , org.codehaus.plexus.logging.Logger
  {
    private final StringBuffer sb = new StringBuffer();
    private final String ls = System.lineSeparator();

    public MemImpl() {
      this("MemImpl");
    }

    public MemImpl(String name) {
      super(name);
    }

    @Override
    public String toString() {
      return sb.toString();
    }

    @Override
    protected void logit(int level, CharSequence format, Object... arguments) {
      if (isEnabled(level)) {
        FormattingTuple tp = MessageFormatter.arrayFormat(String.valueOf(format), arguments);
        String msg = tp.getMessage();
        Throwable th = tp.getThrowable();
        sb.append(msg).append(ls);
        if (th != null) {
          sb.append("    ").append(th);
        }
      }
    }

    @Override
    public void debug(CharSequence content) {
      logit(LEVEL_DEBUG, content);
    }

    @Override
    public void debug(CharSequence content, Throwable error) {
      logit(LEVEL_DEBUG, content, error);
    }

    @Override
    public void debug(Throwable error) {}

    public void info(CharSequence content) {
      logit(LEVEL_INFO, content);
    }

    @Override
    public void info(Throwable error) {
      logit(LEVEL_INFO, error.getMessage(), error);
    }

    @Override
    public void info(CharSequence content, Throwable error) {
      logit(LEVEL_INFO, content, error);
    }

    @Override
    public void warn(CharSequence content) {}

    @Override
    public void warn(CharSequence content, Throwable error) {}

    @Override
    public void warn(Throwable error) {}

    @Override
    public void error(CharSequence content) {}

    @Override
    public void error(CharSequence content, Throwable error) {}

    @Override
    public void error(Throwable error) {}
  }

  class Slf4jImpl extends AbstractImpl implements LoggerEx, org.slf4j.Logger {

    private final org.slf4j.Logger impl;

    public Slf4jImpl(org.slf4j.Logger impl) {
      this.impl = impl;
    }

    @Override
    public int getThreshold() {
      if (impl.isErrorEnabled()) return LEVEL_ERROR;
      if (impl.isWarnEnabled()) return LEVEL_WARN;
      if (impl.isInfoEnabled()) return LEVEL_INFO;
      if (impl.isDebugEnabled()) return LEVEL_DEBUG;
      if (impl.isTraceEnabled()) return LEVEL_TRACE;
      return LEVEL_DISABLED;
    }

    @Override
    public void setThreshold(int threshold) {
      throw new UnsupportedOperationException("Unable to change threshhold");
    }

    protected void logit(int level, CharSequence format0, Object... arguments) {
      String format = String.valueOf(format0);
      switch (level) {
        case LEVEL_FATAL -> impl.error(format, arguments);
        case LEVEL_ERROR -> impl.error(format, arguments);
        case LEVEL_WARN -> impl.warn(format, arguments);
        case LEVEL_INFO -> impl.info(format, arguments);
        case LEVEL_DEBUG -> impl.debug(format, arguments);
        case LEVEL_TRACE -> impl.trace(format, arguments);
      }
    }
  }

  @RequiredArgsConstructor
  class PlexusImpl extends AbstractImpl implements LoggerEx, org.codehaus.plexus.logging.Logger {

    private final org.codehaus.plexus.logging.Logger impl;

    @Override
    public int getThreshold() {
      return impl.getThreshold();
    }

    @Override
    public void setThreshold(int threshold) {
      impl.setThreshold(threshold);
    }

    @Override
    protected void logit(int level, CharSequence format, Object... arguments) {
      FormattingTuple tp = MessageFormatter.arrayFormat(String.valueOf(format), arguments);
      String msg = tp.getMessage();
      Throwable th = tp.getThrowable();
      if (th == null) {
        msgimpl(level).accept(msg);
      } else {
        errimpl(level).accept(msg, th);
      }
    }

    private Consumer<String> msgimpl(int level) {
      return switch (level) {
        case LoggerEx.LEVEL_INFO -> impl::info;
        case LoggerEx.LEVEL_WARN -> impl::warn;
        case LoggerEx.LEVEL_ERROR -> impl::error;
        case LoggerEx.LEVEL_FATAL -> impl::fatalError;
        case LoggerEx.LEVEL_DEBUG -> impl::debug;
        default -> msg -> {};
      };
    }

    private BiConsumer<String, Throwable> errimpl(int level) {
      return switch (level) {
        case LoggerEx.LEVEL_INFO -> impl::info;
        case LoggerEx.LEVEL_WARN -> impl::warn;
        case LoggerEx.LEVEL_ERROR -> impl::error;
        case LoggerEx.LEVEL_FATAL -> impl::fatalError;
        case LoggerEx.LEVEL_DEBUG -> impl::debug;
        default -> (msg, th) -> {};
      };
    }
  }
}
