package org.emergent.gittle.maven.plugin;

import lombok.Getter;
import lombok.Setter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.emergent.gittle.core.Constants;
import org.emergent.gittle.core.GittleException;

@Getter
@Setter
public abstract class AbstractGittleMojo extends org.apache.maven.plugin.AbstractMojo {

  private static final String DOTTED_PREFIX = Constants.GITTLE + ".";

  @Parameter(name = "skip", defaultValue = "false", property = "gittle.skip")
  protected boolean skip;

  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  protected MavenProject mavenProject;

  @Parameter(defaultValue = "${session}", required = true, readonly = true)
  protected MavenSession mavenSession;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    try {
      execute0();
    } catch (MojoExecutionException | MojoFailureException | Error e) {
      throw e;
    } catch (Exception e) {
      throw new GittleException(e.getMessage(), e);
    }
  }

  protected void execute0() throws Exception {}
}
