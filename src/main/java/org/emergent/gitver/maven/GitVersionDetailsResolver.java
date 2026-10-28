package org.emergent.gitver.maven;

import static org.emergent.gitver.maven.GitRefType.BRANCH;
import static org.emergent.gitver.maven.GitRefType.COMMIT;
import static org.emergent.gitver.maven.GitRefType.TAG;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.emergent.gitver.maven.Configuration.RefPatchDescription;
import org.jspecify.annotations.NullUnmarked;

/** Selects the configured ref (tag, branch or commit) that matches a {@link GitSituation}. */
@NullUnmarked
final class GitVersionDetailsResolver {

  private GitVersionDetailsResolver() {}

  /**
   * @return the matching details, or null if no ref configuration matches
   */
  static GitVersionDetails resolve(GitSituation gitSituation, Configuration config) {
    final Lazy<List<String>> sortedTags = Lazy.by(gitSituation::getTags);
    for (RefPatchDescription refConfig : config.getRefs()) {
      if (refConfig.updateAttachedPom == null)
        refConfig.updateAttachedPom = config.getUpdateAttachedPom();
      switch (refConfig.type) {
        case TAG:
          {
            if (gitSituation.isDetached() || config.getConsiderTagsOnBranches()) {
              for (String tag : sortedTags.get()) {
                Pattern pattern =
                    Optional.ofNullable(refConfig.pattern).map(Pattern::compile).orElse(null);
                if (pattern == null || pattern.matcher(tag).matches()) {
                  return new GitVersionDetails(gitSituation.getRev(), TAG, tag, refConfig);
                }
              }
            }
          }
          break;
        case BRANCH:
          {
            if (!gitSituation.isDetached()) {
              String branch = gitSituation.getBranch();
              Pattern pattern =
                  Optional.ofNullable(refConfig.pattern).map(Pattern::compile).orElse(null);
              if (pattern == null || pattern.matcher(branch).matches()) {
                return new GitVersionDetails(gitSituation.getRev(), BRANCH, branch, refConfig);
              }
            }
          }
          break;
        default:
          throw new IllegalArgumentException("Unexpected ref type: " + refConfig.type);
      }
    }

    if (config.getRev() != null) {
      if (config.getRev().updateAttachedPom == null)
        config.getRev().updateAttachedPom = config.getUpdateAttachedPom();
      return new GitVersionDetails(
          gitSituation.getRev(),
          COMMIT,
          gitSituation.getRev(),
          new RefPatchDescription(COMMIT, null, config.getRev()));
    }

    return null;
  }
}
