package org.emergent.gitver.maven;

import static java.util.Objects.requireNonNull;

import org.emergent.gitver.maven.Configuration.RefPatchDescription;

record GitVersionDetails(
    String commit, GitRefType refType, String refName, RefPatchDescription patchDescription) {
  public GitVersionDetails {
    requireNonNull(commit);
    requireNonNull(refType);
    requireNonNull(refName);
    requireNonNull(patchDescription);
  }
}
