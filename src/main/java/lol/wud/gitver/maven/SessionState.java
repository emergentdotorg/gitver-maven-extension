package lol.wud.gitver.maven;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.Getter;
import org.apache.maven.model.Model;

/**
 * What the extension knows about one maven session: the session, the versioning context resolved
 * when the first project is read, and the models versioned so far.
 *
 * <p>A new instance is started with every session, so nothing carries over between the builds of a
 * long-lived maven process such as mvnd or an IDE.
 */
class SessionState {

    @Getter
    private final GitverSession session;

    private boolean contextResolved;
    private Optional<GitverContext> context = Optional.empty();

    @Getter
    private final Map<Path, Model> versionedModels = new ConcurrentHashMap<>();

    public SessionState(GitverSession session) {
        this.session = session;
    }

    /**
     * @param resolver resolves the context on the first call of the session
     * @return the context, or empty when versioning is disabled or not applicable
     */
    public synchronized Optional<GitverContext> context(Supplier<Optional<GitverContext>> resolver) {
        if (!contextResolved) {
            context = resolver.get();
            contextResolved = true;
        }
        return context;
    }
}
