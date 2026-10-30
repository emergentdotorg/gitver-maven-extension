package org.emergent.gitver.maven;

import static java.util.Objects.requireNonNull;

import org.emergent.gitver.git.GitRefType;
import org.emergent.gitver.maven.Configuration.RefConfig;

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
