package org.emergent.gittle.maven.plugin;

import lombok.Getter;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

@Getter
public abstract class AbstractDefinePropertyMojo extends org.apache.maven.plugin.AbstractMojo {

  @Parameter(readonly = true, defaultValue = "${project}")
  protected MavenProject project;

  protected void defineProperty(String name, String value) {
    if (getLog().isDebugEnabled()) {
      getLog().debug("define property " + name + " = \"" + value + "\"");
    }

    project.getProperties().put(name, value);
  }
}
