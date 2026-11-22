package lol.wud.gitver.maven;

import static lol.wud.gitver.maven.Constants.REVISION;
import static lol.wud.gitver.maven.Constants.REVISION_PLACEHOLDER;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.jspecify.annotations.Nullable;

/** The parts of the {@link MavenSession} the extension needs while models are being read. */
@Slf4j
record GitverSession(
        Instant start, MavenExecutionRequest request, AtomicReference<@Nullable String> inheritedRevision) {

    public GitverSession(Instant start, MavenExecutionRequest request) {
        this(start, request, new AtomicReference<>());
    }

    public static GitverSession from(MavenSession session) {
        return new GitverSession(session.getStartTime().toInstant(), session.getRequest());
    }

    public Properties userProperties() {
        return request.getUserProperties();
    }

    public Properties systemProperties() {
        return request.getSystemProperties();
    }

    /**
     * The project version to log. A <code>${revision}</code> version is shown resolved: from the
     * session, the model, or else the value of a project processed earlier, typically the parent.
     */
    public String resolveRevision(String version) {
        return resolveRevision(version, null).orElse(version);
    }

    /**
     * The project version to log. A <code>${revision}</code> version is shown resolved: from the
     * session, the model, or else the value of a project processed earlier, typically the parent.
     */
    public Coords resolveRevision(Model projectModel) {
        Coords coords = Coords.of(projectModel);
        Optional<String> resolved = resolveRevision(coords.getVersion(), projectModel.getProperties());
        return resolved.map(ver -> coords.toBuilder().setVersion(ver).build()).orElseGet(() -> {
            log.warn("no value found for {} in {}", Constants.REVISION_PLACEHOLDER, projectModel.getPomFile());
            return coords;
        });
    }

    private Optional<String> resolveRevision(String projectVersion, @Nullable Properties projectProperties) {
        if (!REVISION_PLACEHOLDER.equals(projectVersion)) {
            return Optional.of(projectVersion);
        }
        Optional<String> ver = Stream.of(request.getUserProperties(), request.getSystemProperties(), projectProperties)
                .filter(Objects::nonNull)
                .map(properties -> properties.getProperty(REVISION))
                .filter(Objects::nonNull)
                .findFirst();
        String inherited = inheritedRevision.updateAndGet(cur -> cur != null ? cur : ver.orElse(null));
        return ver.or(() -> Optional.ofNullable(inherited));
    }
}
