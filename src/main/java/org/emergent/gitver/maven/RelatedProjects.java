package org.emergent.gitver.maven;

import static java.util.stream.Collectors.toSet;
import static org.emergent.gitver.maven.MavenUtil.pomFile;
import static org.emergent.gitver.maven.MavenUtil.readModel;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.jspecify.annotations.NullUnmarked;

/**
 * The set of projects (parents, modules, configured extras) whose versions are managed together
 * with the current project.
 */
@NullUnmarked
final class RelatedProjects {

  private final Set<Coords> coords;
  private final File mvnProjectRoot;
  private final File gitRoot;

  private RelatedProjects(Set<Coords> coords, File mvnProjectRoot, File gitRoot) {
    this.coords = coords;
    this.mvnProjectRoot = mvnProjectRoot;
    this.gitRoot = gitRoot;
  }

  static RelatedProjects determine(
      Model projectModel, Configuration config, File mvnDirectory, File gitRoot)
      throws IOException {
    final Set<Coords> found = new HashSet<>();
    final RelatedProjects result =
        new RelatedProjects(found, mvnDirectory.getParentFile(), gitRoot);
    result.collect(projectModel);
    config.getRelatedProjects().stream()
        .map(it -> Coords.of(it.groupId, it.artifactId, "*"))
        .forEach(found::add);
    return result;
  }

  Set<Coords> coords() {
    return coords;
  }

  boolean contains(Coords project) {
    return Stream.of(project, Coords.of(project.getGroupId(), project.getArtifactId(), "*"))
        .anyMatch(coords::contains);
  }

  private void collect(Model projectModel) throws IOException {
    final Coords projectCoords = Coords.of(projectModel);
    if (coords.contains(projectCoords)) {
      return;
    }

    // add self
    coords.add(projectCoords);

    // check for related parent project by parent tag
    if (projectModel.getParent() != null) {
      final Coords parentCoords = Coords.of(projectModel.getParent());
      final File parentProjectPomFile = getParentProjectPomFile(projectModel);
      if (isRelatedPom(parentProjectPomFile)) {
        final Model parentProjectModel = readModel(parentProjectPomFile);
        final Coords parentProjectCoords = Coords.of(parentProjectModel);
        if (parentProjectCoords.equals(parentCoords)) {
          collect(parentProjectModel);
        }
      }
    }

    // check for related parent project within parent directory
    final Model parentProjectModel = searchParentProjectInParentDirectory(projectModel);
    if (parentProjectModel != null) {
      collect(parentProjectModel);
    }

    //  process modules
    for (File modulePomFile : getProjectModules(projectModel)) {
      collect(readModel(modulePomFile));
    }
  }

  /**
   * checks if <code>pomFile</code> is part of current maven and git context
   *
   * @param pomFile the pom file
   * @return true if <code>pomFile</code> is part of current maven and git context
   */
  private boolean isRelatedPom(File pomFile) throws IOException {
    return pomFile != null
        && pomFile.exists()
        && pomFile.isFile()
        // only project pom files ends in .xml, pom files from dependencies from repositories ends
        // in .pom
        && pomFile.getName().endsWith(".xml")
        && pomFile.getCanonicalPath().startsWith(mvnProjectRoot.getCanonicalPath() + File.separator)
        // only pom files within git directory are treated as project pom files
        && pomFile.getCanonicalPath().startsWith(gitRoot.getCanonicalPath() + File.separator);
  }

  private Model searchParentProjectInParentDirectory(Model projectModel) throws IOException {
    // search for parent project by directory hierarchy
    File parentDirectoryPomFile =
        pomFile(projectModel.getProjectDirectory().getParentFile(), "pom.xml");
    if (parentDirectoryPomFile.exists() && isRelatedPom(parentDirectoryPomFile)) {
      // check if parent has module that points to current project directory
      Model parentDirectoryProjectModel = readModel(parentDirectoryPomFile);
      for (File modulePomFile : getProjectModules(parentDirectoryProjectModel)) {
        if (modulePomFile.getCanonicalFile().equals(projectModel.getPomFile().getCanonicalFile())) {
          return parentDirectoryProjectModel;
        }
      }
    }
    return null;
  }

  private static File getParentProjectPomFile(Model projectModel) {
    if (projectModel.getParent() == null) {
      return null;
    }

    File parentProjectPomFile =
        pomFile(projectModel.getProjectDirectory(), projectModel.getParent().getRelativePath());
    if (parentProjectPomFile.exists()) {
      return parentProjectPomFile;
    }

    return null;
  }

  private static Set<File> getProjectModules(Model projectModel) {
    final Set<File> modules = new HashSet<>();

    // modules section
    for (String module : projectModel.getModules()) {
      modules.add(pomFile(projectModel.getProjectDirectory(), module));
    }

    // profiles section
    for (Profile profile : projectModel.getProfiles()) {
      for (String module : profile.getModules()) {
        modules.add(pomFile(projectModel.getProjectDirectory(), module));
      }
    }

    return modules.stream().filter(File::exists).collect(toSet());
  }
}
