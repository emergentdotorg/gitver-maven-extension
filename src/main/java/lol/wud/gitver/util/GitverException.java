package lol.wud.gitver.util;

/** The unchecked exception the extension throws in place of checked I/O and parsing failures. */
public class GitverException extends RuntimeException {

  /**
   * @return <code>cause</code> itself if unchecked, otherwise wrapped in a GitverException
   */
  public static RuntimeException wrap(Exception cause) {
    return cause instanceof RuntimeException runtime ? runtime : new GitverException(cause);
  }

  public GitverException(String message) {
    super(message);
  }

  public GitverException(Throwable cause) {
    super(cause);
  }

  public GitverException(String message, Throwable cause) {
    super(message, cause);
  }
}
