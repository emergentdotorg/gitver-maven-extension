package lol.wud.gitver.git;

import java.io.File;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import lol.wud.gitver.util.GitverException;
import lol.wud.gitver.util.Memoized;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

@Slf4j
public class GitSituationFactory {

    private final Function<String, @Nullable String> environment;

    public GitSituationFactory() {
        this(System.getenv());
    }

    public GitSituationFactory(Map<String, String> environment) {
        this(environment::get);
    }

    public GitSituationFactory(Function<String, @Nullable String> environment) {
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

        GitOps git = new GitOps(directory.toPath(), environment);
        Result toplevel = git.invokeWithResult("rev-parse", "--show-toplevel");
        if (!toplevel.ok()) {
            if (toplevel.err().contains("dubious ownership")) {
                // Silently skipping versioning here would hide a fixable configuration problem.
                throw new GitverException("git refuses to read %s: %s".formatted(directory, toplevel.err()));
            }
            log.debug("not in a git work tree: {} - {}", directory, toplevel.err());
            return null;
        }

        Path root = Path.of(toplevel.out());
        GitOps ops = new GitOps(root, environment);
        return new ExecGitSituation(ops);
    }

    @ToString
    private static class ExecGitSituation implements GitSituation {

        private final Path rootDirectory;
        private final Supplier<Boolean> clean;
        private final Supplier<ZonedDateTime> timestamp;
        private final Supplier<GitDescription> description;

        ExecGitSituation(GitOps ops) {
            rootDirectory = ops.getRootDirectory();
            clean = Memoized.by(ops::isClean);
            timestamp = Memoized.by(ops::commitTimestamp);
            description = Memoized.by(ops::description);
        }

        @Override
        public Path getRootDirectory() {
            return rootDirectory;
        }

        @Override
        public boolean isClean() {
            return clean.get();
        }

        @Override
        public ZonedDateTime getTimestamp() {
            return timestamp.get();
        }

        @Override
        public GitDescription getDescription() {
            return description.get();
        }
    }
}
