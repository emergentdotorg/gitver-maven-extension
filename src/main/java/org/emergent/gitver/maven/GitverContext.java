package org.emergent.gitver.maven;

/**
 * Everything needed to version the projects of one build, resolved once from the git repository,
 * the configuration and the command options.
 */
record GitverContext(
    GitSituation gitSituation,
    GitVersionDetails gitVersionDetails,
    RelatedProjects relatedProjects,
    VersionFormatter formatter,
    GitverSession session,
    boolean updateSourcePom,
    boolean updateAttachedPom) {

  boolean isRelated(Coords coords) {
    return relatedProjects.contains(coords);
  }

  ModelUpdater modelUpdater() {
    return new ModelUpdater(formatter, relatedProjects, gitSituation, gitVersionDetails, session);
  }
}
