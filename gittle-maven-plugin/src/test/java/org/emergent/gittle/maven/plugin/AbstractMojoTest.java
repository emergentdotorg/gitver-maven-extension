package org.emergent.gittle.maven.plugin;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecution;
import org.junit.jupiter.api.extension.RegisterExtension;

public abstract class AbstractMojoTest {

  protected MavenSession mavenSession;

  @RegisterExtension
  public ProjectMojoRule rule =
      new ProjectMojoRule() {

        @Override
        public <T extends Mojo> T lookupConfiguredMojo(
            MavenSession session, MojoExecution execution) throws Exception {
          mavenSession = session;
          return super.lookupConfiguredMojo(session, execution);
        }
      };
}
