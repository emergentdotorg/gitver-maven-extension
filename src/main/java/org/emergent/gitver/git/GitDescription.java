package org.emergent.gitver.git;

public record GitDescription(String commit, String tag, int distance) {

  @Override
  public String toString() {
    return tag + "-" + distance + "-g" + commit.substring(0, 7);
  }
}
