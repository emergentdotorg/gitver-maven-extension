package org.emergent.gittle.maven;

import static java.util.Objects.requireNonNull;

import org.emergent.gittle.commons.GitRefType;
import org.emergent.gittle.maven.Configuration.RefPatchDescription;

record GitVersionDetails(
    String commit, GitRefType refType, String refName, RefPatchDescription patchDescription) {
  public GitVersionDetails {
    requireNonNull(commit);
    requireNonNull(refType);
    requireNonNull(refName);
    requireNonNull(patchDescription);
  }

  public String getCommit() {
    return commit;
  }

  public GitRefType getRefType() {
    return refType;
  }

  public String getRefName() {
    return refName;
  }

  public RefPatchDescription getPatchDescription() {
    return patchDescription;
  }
}
