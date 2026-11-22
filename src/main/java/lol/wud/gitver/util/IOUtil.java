package lol.wud.gitver.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class IOUtil {

    public static Path canonicalPath(File file) {
        return canonicalPath(file.toPath());
    }

    public static Path canonicalPath(Path path) {
        try {
            return Files.isRegularFile(path) ? path.toRealPath() : path.toAbsolutePath();
        } catch (IOException e) {
            throw new GitverException("Failed to resolve " + path, e);
        }
    }
}
