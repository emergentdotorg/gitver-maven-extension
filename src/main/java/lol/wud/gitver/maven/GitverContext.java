package lol.wud.gitver.maven;

/**
 * Everything needed to version the projects of one build, resolved once from the git repository,
 * the configuration and the command options.
 */
record GitverContext(
    RelatedProjects relatedProjects,
    ModelUpdater modelUpdater,
    boolean updateSourcePom,
    boolean updateAttachedPom) {

  boolean isRelated(Coords coords) {
    return relatedProjects.contains(coords);
  }
}
