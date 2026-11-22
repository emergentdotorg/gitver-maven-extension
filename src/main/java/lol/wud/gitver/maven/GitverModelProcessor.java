package lol.wud.gitver.maven;

import static lol.wud.gitver.util.IOUtil.canonicalPath;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lol.wud.gitver.util.GitverException;
import lol.wud.gitver.util.Memoized;
import lol.wud.gitver.util.StringUtil;
import lol.wud.gitver.util.Throwing;
import lol.wud.gitver.util.VersionFormatter;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.building.Source;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelProcessor;
import org.apache.maven.shared.utils.logging.MessageUtils;

/**
 * Maven entry point. Decides which models to version and orchestrates the three steps:
 *
 * <ol>
 *   <li>gather git information: {@link GitverContextFactory}
 *   <li>calculate the version: {@link VersionFormatter}
 *   <li>update the maven model: {@link ModelUpdater}, {@link PomXmlWriter}, {@link UpdatePomMojo}
 * </ol>
 *
 * Note also, this processor is a singleton that can outlive many maven sessions (e.g. under mvnd),
 * so all per-build state lives in the {@link SessionState} of the running session.
 */
@Slf4j
@Singleton
@Named("core-default")
public class GitverModelProcessor implements ModelProcessor {

    private final GitverSessionHolder sessionHolder;
    private final Supplier<ModelProcessor> delegate;

    @Inject
    public GitverModelProcessor(GitverSessionHolder sessionHolder, List<ModelProcessor> processors) {
        this.sessionHolder = sessionHolder;
        this.delegate = Memoized.by(() -> {
            ModelProcessor foundDelegate = processors.stream()
                    .filter(processor -> processor != this)
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException("Unable to find default ModelProcessor"));
            log.debug("Delegated ModelProcessor : {}", foundDelegate);
            return foundDelegate;
        });
    }

    @Override
    public File locatePom(File projectDirectory) {
        return delegate.get().locatePom(projectDirectory);
    }

    @Override
    public Model read(File input, Map<String, ?> options) throws IOException {
        return processModel(delegate.get().read(input, options), options);
    }

    @Override
    public Model read(Reader input, Map<String, ?> options) throws IOException {
        return processModel(delegate.get().read(input, options), options);
    }

    @Override
    public Model read(InputStream input, Map<String, ?> options) throws IOException {
        return processModel(delegate.get().read(input, options), options);
    }

    public Model processModel(Model projectModel, Map<String, ?> options) throws IOException {
        try {
            Model model = Throwing.uncheck(() -> processModelImpl(projectModel, options));
            // clone to prevent concurrency issues
            return model.clone();
        } catch (GitverException e) {
            // An IOException is attributed as a problem reading this pom.
            throw new IOException(e.getMessage(), e);
        }
    }

    @SuppressWarnings("deprecation")
    private Model processModelImpl(Model projectModel, Map<String, ?> options) {
        // set model pom file
        Source pomSource = (Source) options.get(ModelProcessor.SOURCE);
        if (pomSource != null) {
            projectModel.setPomFile(new File(pomSource.getLocation()));
        } else {
            log.debug("skipping model - missing pom file: {}", Coords.of(projectModel));
            return projectModel;
        }

        File pomFile = projectModel.getPomFile();
        if (!pomFile.getName().endsWith(".xml")) {
            return projectModel;
        }

        SessionState state = sessionHolder.current().orElse(null);
        if (state == null) {
            log.debug("skipping model - missing session - {}", pomFile);
            return projectModel;
        }

        GitverContext context =
                state.context(() -> createContext(state, projectModel)).orElse(null);
        if (context == null) {
            return projectModel;
        }

        Coords projectCoords = Coords.of(projectModel);
        if (StringUtil.isBlank(projectCoords.getVersion())) {
            log.debug("skipping model - indeterminate version - {}", pomFile);
            return projectModel;
        }

        if (!context.relatedProjects().contains(projectCoords)) {
            if (log.isDebugEnabled()) {
                log.debug("skipping model - unrelated project - {}", pomFile);
            }
            return projectModel;
        }

        Path path = canonicalPath(pomFile);
        Model versionedModel = state.getVersionedModels().putIfAbsent(path, projectModel);
        if (versionedModel != null) {
            // already versioned
            return versionedModel;
        }

        if (log.isInfoEnabled()) {
            log.info(MessageUtils.buffer().project(projectCoords.getProjectId()).build());
        }

        context.modelUpdater().update(projectModel);

        PomXmlWriter.write(projectModel, getLocatedPom(projectModel).toPath());

        // The versioned pom can't be set as the model pom immediately because it breaks plugins that
        // also update original pom file (e.g. versions-maven-plugin:set).  Instead, we add a build
        // plugin that sets the project pom to the versioned pom file at execution.
        log.debug("adding {} goal to the build", UpdatePomMojo.GOAL);
        UpdatePomMojo.addToBuild(projectModel);

        log.info("");
        return projectModel;
    }

    private Optional<GitverContext> createContext(SessionState state, Model projectModel) {
        log.info("");
        log.info(MavenUtil.extension(MavenUtil.gitverCoords()));
        File pomFile = getLocatedPom(projectModel);
        if (!pomFile.isFile()) {
            log.debug("skipping - missing pom file: {}", pomFile);
            return Optional.empty();
        }
        return new GitverContextFactory(state.getSession()).create(projectModel, pomFile);
    }

    private File getLocatedPom(Model projectModel) {
        // In case another ModelProcessor is used (e.g. polyglot), we work directly on the pom XML.
        return locatePom(projectModel.getProjectDirectory());
    }
}
