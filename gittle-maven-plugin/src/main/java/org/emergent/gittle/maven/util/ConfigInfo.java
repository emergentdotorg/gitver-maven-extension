package org.emergent.gittle.maven.util;

import java.io.File;
import java.nio.file.Path;

public record ConfigInfo(Path rootDir, Configuration configuration) {

  private ConfigInfo(Configuration configuration, File rootDir) {
    this(rootDir.toPath(), configuration);
  }

  public static ConfigInfo load(Path rootPath) {
    return load(rootPath.toFile());
  }

  public static ConfigInfo load(File rootDirectory) {
    Configuration cfg = ConfigurationLoader.loadFromRoot(rootDirectory);
    return new ConfigInfo(cfg, rootDirectory);
  }
}
