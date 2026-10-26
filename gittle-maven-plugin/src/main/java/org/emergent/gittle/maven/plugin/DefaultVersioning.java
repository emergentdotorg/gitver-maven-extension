package org.emergent.gittle.maven.plugin;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;

/**
 * @author Karl Heinz Marbaise <a href="mailto:khmarbaise@apache.org">khmarbaise@apache.org</a>
 */
@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DefaultVersioning implements Versioning {

  String version;
  VersionInformation vi;

  public DefaultVersioning(String version) {
    this(version, new VersionInformation(version));
  }

  @Override
  public String getAsOSGiVersion() {
    StringBuilder osgiVersion = new StringBuilder();
    osgiVersion.append(getMajor()).append(".").append(getMinor()).append(getPatch());
    long buildNumber = getBuildNumber();
    String qualifier = getQualifier();
    if (qualifier != null || buildNumber != 0) {
      osgiVersion.append(".");
      if (buildNumber != 0) {
        osgiVersion.append(buildNumber);
      }
      if (qualifier != null) {
        // Do not allow having "." in it cause it's not allowed in OSGi.
        String q = qualifier.replaceAll("\\.", "_");
        osgiVersion.append(q);
      }
    }
    return osgiVersion.toString();
  }

  @Override
  public int getMajor() {
    return vi.getMajor();
  }

  @Override
  public int getMinor() {
    return vi.getMinor();
  }

  @Override
  public int getPatch() {
    return vi.getPatch();
  }

  @Override
  public long getBuildNumber() {
    return vi.getBuildNumber();
  }

  @Override
  public String getQualifier() {
    return vi.getQualifier();
  }
}
