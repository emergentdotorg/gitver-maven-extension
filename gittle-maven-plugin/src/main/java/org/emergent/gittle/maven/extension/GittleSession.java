package org.emergent.gittle.maven.extension;

import java.nio.file.Path;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import org.emergent.gittle.maven.util.ConfigInfo;
import org.emergent.gittle.maven.util.Configuration;
import org.jspecify.annotations.NullMarked;

@NullMarked
@Value
@Builder(toBuilder = true, builderClassName = "Builder", setterPrefix = "set")
public class GittleSession {

  ConfigInfo configInfo;
  InfoProvider infoProvider;

  public boolean isDirty() {
    return infoProvider.isDirty();
  }

  public String version() {
    return infoProvider.version().toString();
  }

  public Configuration getConfiguration() {
    return configInfo.configuration();
  }

  public Path getMultiModuleProjectDirectory() {
    return configInfo.rootDir();
  }

  public Map<String, String> getProperties() {
    return ExtensionUtil.getPropertiesFromMetadatas(infoProvider);
  }
}
