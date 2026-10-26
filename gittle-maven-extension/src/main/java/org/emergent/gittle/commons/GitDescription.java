package org.emergent.gittle.commons;

public record GitDescription(String commit, String tag, int distance) {

  public String getCommit() {
    return commit;
  }

  public String getTag() {
    return tag;
  }

  public int getDistance() {
    return distance;
  }

  @Override
  public String toString() {
    return tag + "-" + distance + "-g" + commit.substring(0, 7);
  }
}
