package org.emergent.gitver.git;

import java.time.Instant;
import java.util.Comparator;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.emergent.gitver.maven.Throwing;

public record GitTagComparator(RevWalk revWalk) implements Comparator<Ref> {

  public GitTagComparator(Repository repository) {
    this(new RevWalk(repository));
  }

  @Override
  public int compare(Ref ref1, Ref ref2) {
    RevObject rev1 = Throwing.get(() -> revWalk.parseAny(ref1.getObjectId()));
    RevObject rev2 = Throwing.get(() -> revWalk.parseAny(ref2.getObjectId()));
    if (rev1 instanceof RevTag revTag1 && rev2 instanceof RevTag revTag2) {
      // both annotated tags
      Instant t1 = revTag1.getTaggerIdent().getWhenAsInstant();
      Instant t2 = revTag2.getTaggerIdent().getWhenAsInstant();
      // most recent first
      return -t1.compareTo(t2);
    }
    if (rev1 instanceof RevTag) {
      return -1;
    }
    if (rev2 instanceof RevTag) {
      return 1;
    }
    // both lightweight tags
    ComparableVersion v1 = new ComparableVersion(ref1.getName());
    ComparableVersion v2 = new ComparableVersion(ref2.getName());
    // highest version first
    return -v1.compareTo(v2);
  }
}
