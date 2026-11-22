package lol.wud.gitver.maven;

import static java.lang.Math.ceil;
import static java.lang.Math.floor;
import static java.lang.Math.max;
import static lol.wud.gitver.util.StringUtil.repeat;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.Supplier;
import lol.wud.gitver.util.GitverException;
import lol.wud.gitver.util.Memoized;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Model;
import org.apache.maven.model.ModelBase;
import org.apache.maven.model.Profile;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.shared.utils.logging.MessageUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.Nullable;

@SuppressWarnings("deprecation")
final class MavenUtil {

    private static final String GITVER_PROPERTIES_PATH = "META-INF/maven/gitver.properties";

    private static final Supplier<Coords> gitverCoordsSupplier = Memoized.by(() -> {
        ClassLoader loader = MavenUtil.class.getClassLoader();
        try (InputStream is = loader.getResourceAsStream(GITVER_PROPERTIES_PATH)) {
            Properties props = new Properties();
            props.load(is);
            return Coords.builder()
                    .setGroupId(props.getProperty("project.groupId"))
                    .setArtifactId(props.getProperty("project.artifactId"))
                    .setVersion(props.getProperty("project.version"))
                    .build();
        } catch (IOException e) {
            throw new GitverException("Failed to read " + GITVER_PROPERTIES_PATH, e);
        }
    });

    public static Coords gitverCoords() {
        return gitverCoordsSupplier.get();
    }

    public static Model readModel(File pomFile) {
        return readModel(pomFile.toPath());
    }

    public static Model readModel(Path pomFile) {
        try (BufferedReader reader = Files.newBufferedReader(pomFile, StandardCharsets.UTF_8)) {
            Model model = new MavenXpp3Reader().read(reader);
            model.setPomFile(pomFile.toFile());
            return model;
        } catch (IOException | XmlPullParserException e) {
            throw new GitverException("Failed to read " + pomFile, e);
        }
    }

    public static Path pomFile(Path dir, String relativePath) {
        Path modulePomFile = dir.resolve(relativePath);
        if (Files.isDirectory(modulePomFile)) {
            modulePomFile = modulePomFile.resolve(Constants.POM_XML);
        }
        return modulePomFile;
    }

    public static File pomFile(File dir, String relativePath) {
        File modulePomFile = new File(dir, relativePath);
        if (modulePomFile.isDirectory()) {
            modulePomFile = new File(modulePomFile, Constants.POM_XML);
        }
        return modulePomFile;
    }

    public static @Nullable BuildBase getBuild(ModelBase modelBase) {
        if (modelBase instanceof Profile profile) {
            return profile.getBuild();
        }
        if (modelBase instanceof Model model) {
            return model.getBuild();
        }
        throw new IllegalStateException("Unknown ModelBase subclass: " + modelBase.getClass());
    }

    public static String section(String title, ModelBase model) {
        String header = title + ":";
        if (model instanceof Profile profile) {
            header = MessageUtils.buffer().strong("profile " + profile.getId() + " ") + header;
        }
        return header;
    }

    public static String extension(Coords coords) {
        return logHeader(coords, "[core extension]");
    }

    private static String logHeader(Coords coords, String metaInfo) {
        String plainLog = coords + " " + metaInfo;
        String formattedLog = MessageUtils.buffer().a(" ")
                .mojo(coords.toString())
                .a(" ")
                .strong(metaInfo)
                .a(" ")
                .build();
        String pad = "-";
        int padding = max(6, 72 - 2 - plainLog.length());
        int paddingLeft = (int) floor(padding / 2.0);
        int paddingRight = (int) ceil(padding / 2.0);
        return MessageUtils.buffer().strong(repeat(pad, paddingLeft))
                .a(formattedLog)
                .strong(repeat(pad, paddingRight))
                .build();
    }
}
