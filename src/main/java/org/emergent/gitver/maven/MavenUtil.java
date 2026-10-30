package org.emergent.gitver.maven;

import static java.nio.file.Files.readAllBytes;

import de.pdark.decentxml.Document;
import de.pdark.decentxml.XMLParser;
import de.pdark.decentxml.XMLStringSource;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Model;
import org.apache.maven.model.ModelBase;
import org.apache.maven.model.Profile;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.emergent.gitver.util.Lazy;
import org.jspecify.annotations.Nullable;

@Slf4j
final class MavenUtil {

  private static final Supplier<Properties> buildProperties =
      memoize(
          () -> {
            ClassLoader loader = MavenUtil.class.getClassLoader();
            try (InputStream is = loader.getResourceAsStream(Constants.GITVER_PROPERTIES_PATH)) {
              Properties properties = new Properties();
              properties.load(is);
              return properties;
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          });

  static Model readModel(File pomFile) throws IOException {
    try (FileReader fileReader = new FileReader(pomFile, StandardCharsets.UTF_8)) {
      Model model = new MavenXpp3Reader().read(fileReader);
      model.setPomFile(pomFile);
      return model;
    } catch (XmlPullParserException e) {
      throw new RuntimeException(e);
    }
  }

  static Model readModel(Path pomFile) throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(pomFile, StandardCharsets.UTF_8)) {
      Model model = new MavenXpp3Reader().read(reader);
      model.setPomFile(pomFile.toFile());
      return model;
    } catch (XmlPullParserException e) {
      throw new RuntimeException(e);
    }
  }

  static void writeModel(File pomFile, Model model) throws IOException {
    try (FileWriter fileWriter = new FileWriter(pomFile, StandardCharsets.UTF_8)) {
      new MavenXpp3Writer().write(fileWriter, model);
    }
  }

  public static File pomFile(File workingDirectory, String relativePath) {
    File modulePomFile = new File(workingDirectory, relativePath);
    if (modulePomFile.isDirectory()) {
      modulePomFile = new File(modulePomFile, "pom.xml");
    }
    return modulePomFile;
  }

  public static void writeXml(File file, Document gitVersionedPom) throws IOException {
    String xml = gitVersionedPom.toXML();
    byte[] gitVersionedPomBytes = xml.getBytes(StandardCharsets.UTF_8);
    if (file.exists()) {
      byte[] existingPomBytes = readAllBytes(file.toPath());
      if (Arrays.equals(gitVersionedPomBytes, existingPomBytes)) {
        // do not write if there is no change
        return;
      }
    }

    Files.write(file.toPath(), gitVersionedPomBytes);
  }

  public static Document readXml(File file) throws IOException {
    String pomXml = new String(readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    XMLParser parser = new XMLParser();
    return parser.parse(new XMLStringSource(pomXml));
  }

  public static @Nullable BuildBase getBuild(ModelBase model) {
    if (model instanceof Profile profile) {
      return profile.getBuild();
    }
    return ((Model) model).getBuild();
  }

  public static <T> Supplier<T> memoize(Supplier<T> delegate) {
    return Lazy.by(delegate);
  }

  public static Coords gitverCoords() {
    // Package pkg = MavenUtil.class.getPackage();
    // String implVersion = pkg.getImplementationVersion();
    // String version = StringUtil.isNotBlank(implVersion) ? implVersion : "unknown";
    Properties props = buildProperties.get();
    return Coords.builder()
        // .setGroupId(Constants.MAVEN_GROUP_ID)
        // .setArtifactId(Constants.MAVEN_ARTIFACT_ID)
        // .setVersion(version)
        .setGroupId(props.getProperty("project.groupId"))
        .setArtifactId(props.getProperty("project.artifactId"))
        .setVersion(props.getProperty("project.version"))
        .build();
  }
}
