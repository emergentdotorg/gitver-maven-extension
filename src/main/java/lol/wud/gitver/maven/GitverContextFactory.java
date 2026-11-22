package lol.wud.gitver.maven;

import java.io.File;
import java.util.Optional;
import lol.wud.gitver.git.GitSituation;
import lol.wud.gitver.git.GitSituationFactory;
import lol.wud.gitver.util.GitverException;
import lol.wud.gitver.util.VersionFormatter;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.model.Model;
import org.apache.maven.shared.utils.logging.MessageUtils;

/**
 * Wires the collaborators together: gathers the git situation, picks the
 * matching ref and prepares the version formatter. Returns empty when versioning is not applicable.
 */
@Slf4j
final class GitverContextFactory {

    private final GitverSession session;

    GitverContextFactory(GitverSession session) {
        this.session = session;
    }

    @SuppressWarnings("deprecation")
    Optional<GitverContext> create(Model projectModel, File pomFile) {
        log.debug("pom file: {}", pomFile);
        File mvnDirectory = findMvnDirectory(pomFile);
        log.debug(".mvn directory: {}", mvnDirectory);

        if (isDisabled()) {
            return Optional.empty();
        }

        session.resolveRevision(projectModel);

        GitSituation gitSituation = new GitSituationFactory().create(pomFile);
        if (gitSituation == null) {
            log.warn("skip - project is not part of a git repository");
            return Optional.empty();
        }
        logSituation(gitSituation);

        VersionFormatter formatter = new VersionFormatter(gitSituation);

        RelatedProjects relatedProjects =
                RelatedProjects.create(projectModel, mvnDirectory.toPath(), gitSituation.getRootDirectory());
        if (log.isDebugEnabled()) {
            log.debug(MessageUtils.buffer().strong("related projects:").build());
            relatedProjects.coords().forEach(gav -> log.debug("  {}", gav));
        }

        log.info("");
        ModelUpdater modelUpdater = new ModelUpdater(formatter, relatedProjects, gitSituation, session);
        return Optional.of(new GitverContext(relatedProjects, modelUpdater));
    }

    private boolean isDisabled() {
        boolean disabled = Boolean.parseBoolean(System.getenv("GITVER_DISABLE"));
        if (disabled) {
            log.info("skip - versioning is disabled by command option");
        }
        return disabled;
    }

    private static File findMvnDirectory(File pomFile) {
        File searchDirectory = pomFile.getParentFile();
        while (searchDirectory != null) {
            File mvnDir = new File(searchDirectory, ".mvn");
            if (mvnDir.exists()) {
                return mvnDir;
            }
            searchDirectory = searchDirectory.getParentFile();
        }

        throw new GitverException("Can not find .mvn directory in hierarchy of " + pomFile);
    }

    private static void logSituation(GitSituation gitSituation) {
        if (log.isDebugEnabled()) {
            log.debug("git situation:");
            log.debug("  root directory: {}", gitSituation.getRootDirectory());
            // log.debug("  head commit: {}", gitSituation.getRev());
            // log.debug("  head commit timestamp: {}", gitSituation.getTimestamp());
            // log.debug("  head branch: {}", gitSituation.getBranch());
            // log.debug("  head tags: {}", gitSituation.getTags());
            log.debug("  head description: {}", gitSituation.getDescription());
        }
    }
}
