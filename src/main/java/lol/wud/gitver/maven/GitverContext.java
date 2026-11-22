package lol.wud.gitver.maven;

/**
 * Everything needed to version the projects of one build, resolved from the git repository and the command options.
 */
record GitverContext(RelatedProjects relatedProjects, ModelUpdater modelUpdater) {}
