package org.emergent.gitver.maven;

import static java.util.Objects.requireNonNull;
import static org.emergent.gitver.git.GitRefType.COMMIT;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.Builder.Default;
import lombok.Singular;
import lombok.Value;
import org.emergent.gitver.git.GitRefType;
import org.emergent.gitver.util.Memoized;
import org.emergent.gitver.util.Throwing;
import org.jspecify.annotations.Nullable;

@Value
@lombok.Builder(toBuilder = true, setterPrefix = "set")
public class Configuration {

  private static final Supplier<Configuration> DEFAULT_SUPPLIER =
      Memoized.by(
          () -> {
            String name = "schema/gitver-defaults.xml";
            ClassLoader loader = Configuration.class.getClassLoader();
            return ConfigurationXmlReader.read(requireNonNull(loader.getResource(name)));
          });

  @Default boolean disable = false;
  @Default boolean considerTagsOnBranches = false;
  @Nullable String projectVersionPattern;
  @Default RefConfig topConfig = RefConfig.getDefault();
  @Singular List<RefConfig> refs;
  @Singular List<Coords> relatedProjects;

  public static Configuration getDefault() {
    return DEFAULT_SUPPLIER.get();
  }

  /**
   * @return the configuration in <code>configFile</code>, or the defaults if it does not exist
   */
  public static Configuration readOrDefault(Path configFile) {
    Configuration defs = getDefault();
    if (!Files.isRegularFile(configFile)) {
      return defs;
    }
    String content = Throwing.get(() -> Files.readString(configFile));
    return ConfigurationXmlReader.read(content, defs);
  }

  public Optional<String> getProjectVersionPattern() {
    return Optional.ofNullable(projectVersionPattern);
  }

  public String getTagPattern() {
    return topConfig.getTagPattern();
  }

  public boolean isUpdateAttachedPom() {
    return topConfig.isUpdateAttachedPom();
  }

  public boolean isUpdateSourcePom() {
    return topConfig.isUpdateSourcePom();
  }

  public boolean isFollowFirstParentOnly() {
    return topConfig.isFollowFirstParentOnly();
  }

  @Value
  @lombok.Builder(setterPrefix = "set", toBuilder = true)
  public static class RefConfig {

    @Default GitRefType type = COMMIT;
    @Nullable String pattern;
    @Nullable String version;
    @Default String tagPattern = "v(?<ver>.*)";
    @Default boolean updateAttachedPom = true;
    @Default boolean updateSourcePom = false;
    @Default boolean followFirstParentOnly = true;
    @Singular Map<String, String> properties;
    @Singular Map<String, String> userProperties;

    public static RefConfig getDefault() {
      return builder().build();
    }

    public Optional<String> getPattern() {
      return Optional.ofNullable(pattern);
    }

    public Optional<String> getVersion() {
      return Optional.ofNullable(version);
    }
  }
}
