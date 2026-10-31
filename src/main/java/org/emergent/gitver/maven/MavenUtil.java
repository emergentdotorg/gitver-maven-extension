package org.emergent.gitver.maven;

import static java.nio.file.Files.readAllBytes;

import de.pdark.decentxml.Document;
import de.pdark.decentxml.XMLParser;
import de.pdark.decentxml.XMLStringSource;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Properties;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Model;
import org.apache.maven.model.ModelBase;
import org.apache.maven.model.Profile;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.emergent.gitver.util.GitverException;
import org.emergent.gitver.util.Memoized;
import org.jspecify.annotations.Nullable;

@Slf4j
final class MavenUtil {

  private static final Supplier<Properties> buildProperties =
      Memoized.by(
          () -> {
            ClassLoader loader = MavenUtil.class.getClassLoader();
            try (InputStream is = loader.getResourceAsStream(Constants.GITVER_PROPERTIES_PATH)) {
              Properties properties = new Properties();
              properties.load(is);
              return properties;
            } catch (IOException e) {
              throw new GitverException("Failed to read " + Constants.GITVER_PROPERTIES_PATH, e);
            }
          });

  static Model readModel(File pomFile) {
    try (FileReader fileReader = new FileReader(pomFile, StandardCharsets.UTF_8)) {
      Model model = new MavenXpp3Reader().read(fileReader);
      model.setPomFile(pomFile);
      return model;
    } catch (IOException | XmlPullParserException e) {
      throw new GitverException("Failed to read " + pomFile, e);
    }
  }

  static File canonicalFile(File file) {
    try {
      return file.getCanonicalFile();
    } catch (IOException e) {
      throw new GitverException("Failed to resolve " + file, e);
    }
  }

  public static File pomFile(File workingDirectory, String relativePath) {
    File modulePomFile = new File(workingDirectory, relativePath);
    if (modulePomFile.isDirectory()) {
      modulePomFile = new File(modulePomFile, "pom.xml");
    }
    return modulePomFile;
  }

  public static void writeXml(File file, Document gitVersionedPom) {
    byte[] gitVersionedPomBytes = gitVersionedPom.toXML().getBytes(StandardCharsets.UTF_8);
    try {
      // do not write if there is no change
      if (!file.exists() || !Arrays.equals(gitVersionedPomBytes, readAllBytes(file.toPath()))) {
        Files.write(file.toPath(), gitVersionedPomBytes);
      }
    } catch (IOException e) {
      throw new GitverException("Failed to write " + file, e);
    }
  }

  public static Document readXml(File file) {
    try {
      String pomXml = new String(readAllBytes(file.toPath()), StandardCharsets.UTF_8);
      return new XMLParser().parse(new XMLStringSource(pomXml));
    } catch (IOException e) {
      throw new GitverException("Failed to read " + file, e);
    }
  }

  public static @Nullable BuildBase getBuild(ModelBase model) {
    if (model instanceof Profile profile) {
      return profile.getBuild();
    }
    return ((Model) model).getBuild();
  }

  public static Coords gitverCoords() {
    Properties props = buildProperties.get();
    return Coords.builder()
        .setGroupId(props.getProperty("project.groupId"))
        .setArtifactId(props.getProperty("project.artifactId"))
        .setVersion(props.getProperty("project.version"))
        .build();
  }
}
