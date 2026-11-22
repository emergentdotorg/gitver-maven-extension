package lol.wud.gitver.git;

import java.nio.file.Path;
import java.time.ZonedDateTime;

public interface GitSituation {
    Path getRootDirectory();
    boolean isClean();
    ZonedDateTime getTimestamp();

    GitDescription getDescription();

    default String refName() {
        GitDescription desc = getDescription();
        return !desc.isPlainTag() ? desc.tag() : desc.commit();
    }

    default String directory() {
        return getRootDirectory().toAbsolutePath().toString();
    }

    default String commit() {
        return getDescription().commit();
    }

    default String shortCommit() {
        return getDescription().shortCommit();
    }

    default String tag() {
        return getDescription().tag();
    }

    default int distance() {
        return getDescription().distance();
    }
}
