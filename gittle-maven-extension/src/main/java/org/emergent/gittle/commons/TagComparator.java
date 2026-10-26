package org.emergent.gittle.commons;

import java.time.Instant;
import java.util.Comparator;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;

@Value
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class TagComparator implements Comparator<Ref> {

  RevWalk revWalk;

  public TagComparator(Repository repository) {
    this(new RevWalk(repository));
  }

  @Override
  public int compare(Ref ref1, Ref ref2) {
    RevObject rev1 = Throwing.tryUnchecked(() -> revWalk.parseAny(ref1.getObjectId()));
    RevObject rev2 = Throwing.tryUnchecked(() -> revWalk.parseAny(ref2.getObjectId()));
    if (rev1 instanceof RevTag revTag1 && rev2 instanceof RevTag revTag2) {
      return compareTaggerDate(revTag1, revTag2);
    }
    if (rev1 instanceof RevTag) {
      return -1;
    }
    if (rev2 instanceof RevTag) {
      return 1;
    }
    return compareTagVersion(ref1, ref2);
  }

  /** For lightweight tags */
  private static int compareTagVersion(Ref ref1, Ref ref2) {
    DefaultArtifactVersion v1 = new DefaultArtifactVersion(ref1.getName());
    DefaultArtifactVersion v2 = new DefaultArtifactVersion(ref2.getName());
    // highest version first
    return -v1.compareTo(v2);
  }

  /** For annotated tags */
  private static int compareTaggerDate(RevTag rev1, RevTag rev2) {
    Instant t1 = rev1.getTaggerIdent().getWhenAsInstant();
    Instant t2 = rev2.getTaggerIdent().getWhenAsInstant();
    // most recent first
    return -t1.compareTo(t2);
  }
}
