package org.emergent.gittle.maven.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

public class AbstractGittleMojoTest {

  AbstractGittleMojo testMojo =
      new AbstractGittleMojo() {

        {
          mavenProject = new MavenProject();
          mavenProject.setFile(new File("my/pom.xml"));
        }

        @Override
        public void execute() throws MojoExecutionException, MojoFailureException {}
      };

  @Test
  public void getVersionConfig() {
    assertThat(testMojo).isNotNull().extracting("skip").isEqualTo(false);
    assertThat(testMojo).isNotNull();
  }
}
