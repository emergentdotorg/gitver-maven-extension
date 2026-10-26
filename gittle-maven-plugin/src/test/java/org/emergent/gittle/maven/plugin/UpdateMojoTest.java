package org.emergent.gittle.maven.plugin;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.emergent.gittle.core.Util;
import org.junit.jupiter.api.Test;

public class UpdateMojoTest extends AbstractMojoTest {

  @Test
  public void testExecute() throws Exception {
    Path baseDir = Path.of("target/test-classes/project-to-test");

    Path oldPomPath = baseDir.resolve("pom.xml");
    assertThat(oldPomPath).as("POM file").isNotNull().exists();

    // todo: config param?
    String newName = Util.GITTLE_POM_XML;

    Path newPomPath = oldPomPath.resolveSibling(newName);
    Files.writeString(newPomPath, "<project></project>", CREATE, TRUNCATE_EXISTING);
    // assertThat(newPom).as("New pom file").isNotNull().exists();

    UpdateMojo mojo = rule.lookupConfiguredMojo(baseDir.toFile(), UpdateMojo.MOJO_NAME);
    assertThat(mojo).isNotNull();

    Path anteriorPom = mojo.getMavenProject().getFile().toPath();
    assertThat(anteriorPom).as("Old pom file").isNotNull().isNotEmptyFile();

    mojo.execute();

    Path posteriorPom = mojo.getMavenProject().getFile().toPath();
    assertThat(posteriorPom)
        .as("New pom file")
        .isNotNull()
        .isNotEqualTo(anteriorPom)
        .isEqualTo(baseDir.resolve(newName));
  }
}
