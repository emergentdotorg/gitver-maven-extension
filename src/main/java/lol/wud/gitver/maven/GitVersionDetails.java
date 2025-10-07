package lol.wud.gitver.maven;

import static java.util.Objects.requireNonNull;

import lol.wud.gitver.git.GitRefType;
import lol.wud.gitver.maven.Configuration.RefConfig;

record GitVersionDetails(String commit, String refName, RefConfig patchOptions) {
  public GitVersionDetails {
    requireNonNull(commit);
    requireNonNull(refName);
    requireNonNull(patchOptions);
  }

  public GitRefType refType() {
    return patchOptions.getType();
  }
}
