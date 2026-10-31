package org.emergent.gitver.util;

public class GitverException extends RuntimeException {

  public static RuntimeException wrap(Exception cause) {
    return new GitverException(cause);
  }

  public GitverException() {}

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
