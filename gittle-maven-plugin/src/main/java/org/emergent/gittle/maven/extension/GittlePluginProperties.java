package org.emergent.gittle.maven.extension;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.function.Supplier;
import org.emergent.gittle.core.Util;

public class GittlePluginProperties {

  private static final String META_INF_PROPERTIES =
      "/META-INF/gittle-maven-plugin-project.properties";

  private static final Supplier<Properties> props =
      Util.memoize(
          () -> {
            Properties p = new Properties();
            try (InputStream is =
                GittlePluginProperties.class.getResourceAsStream(META_INF_PROPERTIES)) {
              if (is != null) {
                p.load(is);
              }
            } catch (IOException ignored) {
            }
            return p;
          });

  public static String getVersion() {
    return get("version", "Unknown");
  }

  public static String getSHA1() {
    return get("sha1", "not git sha1");
  }

  private static String get(String name, String def) {
    return props.get().getProperty(name, def);
  }
}
