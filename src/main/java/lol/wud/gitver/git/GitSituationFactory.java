package lol.wud.gitver.git;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import lol.wud.gitver.util.GitverException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

@Slf4j
public class GitSituationFactory {

    private final Map<String, String> environment;

    public GitSituationFactory() {
        this(System.getenv());
    }

    public GitSituationFactory(Map<String, String> environment) {
        this.environment = environment;
    }

    /**
     * @return the situation of the repository containing the pom, or null if there is none
     * @throws GitverException if git is unavailable or refuses to read the repository
     */
    public @Nullable GitSituation create(File pomFile) {
        File directory = pomFile.getAbsoluteFile().getParentFile();
        if (directory == null || !directory.isDirectory()) {
            log.debug("no directory for pom: {}", pomFile);
            return null;
        }

        Git git = new Git(directory.toPath());
        Git.Result toplevel = git.run("rev-parse", "--show-toplevel");
        if (!toplevel.ok()) {
            if (toplevel.err().contains("dubious ownership")) {
                // Silently skipping versioning here would hide a fixable configuration problem.
                throw new GitverException("git refuses to read %s: %s".formatted(directory, toplevel.err()));
            }
            log.debug("not in a git work tree: {} - {}", directory, toplevel.err());
            return null;
        }

        File root = Path.of(toplevel.out()).toFile();
        return new ExecGitSituation(new Git(root.toPath()), root, environment.get("GIT_TAG"));
    }
}
