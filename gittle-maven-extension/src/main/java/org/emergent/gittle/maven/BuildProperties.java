package org.emergent.gittle.maven;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

final class BuildProperties {

  private static final String FILE_PATH = "META-INF/maven/gittle.properties";

  private static final Properties PROPERTIES = loadProperties();

  private static Properties loadProperties() {
    try (InputStream is = BuildProperties.class.getClassLoader().getResourceAsStream(FILE_PATH)) {
      Properties properties = new Properties();
      properties.load(is);
      return properties;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static String property(String key) {
    return PROPERTIES.getProperty(key);
  }

  public static String projectGroupId() {
    return property("project.groupId");
  }

  public static String projectArtifactId() {
    return property("project.artifactId");
  }

  public static String projectVersion() {
    return property("project.version");
  }

  public static Coords projectGAV() {
    return Coords.builder()
        .setGroupId(projectGroupId())
        .setArtifactId(projectArtifactId())
        .setVersion(projectVersion())
        .build();
  }
}
