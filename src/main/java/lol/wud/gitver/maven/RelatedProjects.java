package lol.wud.gitver.maven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lol.wud.gitver.util.IOUtil;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;

/**
 * The set of projects (parents and modules) whose versions are managed together.
 */
record RelatedProjects(Set<Coords> coords) {

    RelatedProjects {
        coords = Collections.unmodifiableSortedSet(new TreeSet<>(coords));
    }

    public static RelatedProjects create(Model projectModel, Path mvnDir, Path gitRoot) {
        return create(projectModel, mvnDir.toFile(), gitRoot.toFile());
    }

    public static RelatedProjects create(Model projectModel, File mvnDir, File gitRoot) {
        Set<Coords> coords = new HashSet<>();
        collect(coords, IOUtil.canonicalPath(mvnDir.getParentFile()), IOUtil.canonicalPath(gitRoot), projectModel);
        return new RelatedProjects(coords);
    }

    public boolean contains(Coords project) {
        return coords.contains(project.sansVersion());
    }

    private static void collect(Set<Coords> coords, Path mvnRoot, Path gitRoot, Model projectModel) {
        if (!coords.add(Coords.of(projectModel).sansVersion())) {
            return;
        }

        Predicate<Path> isRelated = pom -> isRelated(mvnRoot, gitRoot, pom);

        Optional.ofNullable(projectModel.getParent())
                .map(Parent::getRelativePath)
                .map(relpath -> MavenUtil.pomFile(projectModel.getProjectDirectory(), relpath).toPath())
                .filter(Files::isRegularFile)
                .filter(isRelated)
                .map(MavenUtil::readModel)
                .filter(pm -> Coords.of(projectModel.getParent()).equals(Coords.of(pm)))
                .ifPresent(model -> collect(coords, mvnRoot, gitRoot, model));

        // check for related parent project within parent directory
        // search for parent project by directory hierarchy
        Optional.ofNullable(projectModel.getProjectDirectory())
                .map(file -> file.toPath().toAbsolutePath().getParent())
                .map(path -> path.resolve(Constants.POM_XML))
                .filter(Files::isRegularFile)
                .filter(isRelated)
                .map(MavenUtil::readModel)
                .filter(model -> {
                    // check if parent has module that points to current project directory
                    Path pomPath = IOUtil.canonicalPath(projectModel.getPomFile());
                    return getModules(model).stream().map(IOUtil::canonicalPath).anyMatch(pomPath::equals);
                })
                .ifPresent(model -> collect(coords, mvnRoot, gitRoot, model));

        //  process modules
        getModules(projectModel)
                .forEach(modulePom -> collect(coords, mvnRoot, gitRoot, MavenUtil.readModel(modulePom)));
    }

    private static boolean isRelated(Path mvnRoot, Path gitRoot, Path pomFile) {
        Path pomPath = IOUtil.canonicalPath(pomFile);
        return Files.isRegularFile(pomPath)
                // only project pom files ends in .xml, dependency poms from repositories ends in .pom
                && pomPath.toString().endsWith(".xml")
                && pomPath.startsWith(mvnRoot)
                // only pom files within git directory are treated as project pom files
                && pomPath.startsWith(gitRoot);
    }

    private static Set<Path> getModules(Model projectModel) {
        Path projectDir = projectModel.getProjectDirectory().toPath();
        return Stream.concat(
                        projectModel.getModules().stream(),
                        projectModel.getProfiles().stream().flatMap(profile -> profile.getModules().stream()))
                .map(module -> MavenUtil.pomFile(projectDir, module))
                .filter(Files::isRegularFile)
                .collect(Collectors.toSet());
    }
}
