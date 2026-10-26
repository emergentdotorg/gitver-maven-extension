package org.emergent.gittle.maven.plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.emergent.gittle.core.Util;

@Mojo(name = UpdateMojo.MOJO_NAME, defaultPhase = LifecyclePhase.INITIALIZE)
public class UpdateMojo extends AbstractGittleMojo {

  public static final String MOJO_NAME = "update";

  @Override
  protected void execute0() throws Exception {
    Path basedir = mavenProject.getBasedir().toPath();
    // Path targetdir = basedir.resolve(mavenProject.getBuild().getDirectory());
    Path newPom = basedir.resolve(Util.GITTLE_POM_XML);
    if (Files.exists(newPom)) {
      mavenProject.setPomFile(newPom.toFile());
    } else {
      // TODO: maybe eat it?
      throw new MojoExecutionException("Cannot find " + newPom);
    }
  }
}
