package org.emergent.gittle.maven.plugin;

public interface Versioning {

  int getMajor();

  int getMinor();

  int getPatch();

  String getAsOSGiVersion();

  long getBuildNumber();

  String getQualifier();
}
