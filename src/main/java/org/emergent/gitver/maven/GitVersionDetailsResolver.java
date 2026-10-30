package org.emergent.gitver.maven;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.emergent.gitver.git.GitSituation;
import org.emergent.gitver.maven.Configuration.RefConfig;
import org.emergent.gitver.util.Memoized;

/** Selects the configured ref (tag, branch or commit) that matches a {@link GitSituation}. */
final class GitVersionDetailsResolver {

  private GitVersionDetailsResolver() {}

  static Optional<GitVersionDetails> resolve(GitSituation gitSituation, Configuration config) {
    Memoized<List<String>> sortedTags = Memoized.by(gitSituation::getTags);
    for (RefConfig refConfig : config.getRefs()) {
      switch (refConfig.getType()) {
        case TAG -> {
          if (gitSituation.isDetached() || config.isConsiderTagsOnBranches()) {
            Optional<Pattern> pattern = refConfig.getPattern().map(Pattern::compile);
            for (String tag : sortedTags.get()) {
              if (pattern.isEmpty() || pattern.get().asMatchPredicate().test(tag)) {
                return Optional.of(new GitVersionDetails(gitSituation.getRev(), tag, refConfig));
              }
            }
          }
        }
        case BRANCH -> {
          if (!gitSituation.isDetached()) {
            Optional<Pattern> pattern = refConfig.getPattern().map(Pattern::compile);
            String branch = Objects.requireNonNull(gitSituation.getBranch());
            if (pattern.isEmpty() || pattern.get().asMatchPredicate().test(branch)) {
              return Optional.of(new GitVersionDetails(gitSituation.getRev(), branch, refConfig));
            }
          }
        }
        case COMMIT -> {
          if (gitSituation.isDetached()) {
            String commit = gitSituation.getRev();
            return Optional.of(new GitVersionDetails(commit, commit, refConfig));
          }
        }
        default ->
            throw new IllegalArgumentException("Unexpected ref type: " + refConfig.getType());
      }
    }

    return Optional.empty();
  }
}
