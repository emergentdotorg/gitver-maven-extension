package lol.wud.gitver.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PomXmlWriterTest {

    private static final String POM = """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>test.group</groupId>
          <artifactId>project</artifactId>
          <!-- the version -->
          <version>${revision}</version>
          <properties>
            <revision>1.0.0</revision>
            <other>value</other>
          </properties>
          <build>
            <pluginManagement>
              <plugins>
                <plugin>
                  <groupId>test.group</groupId>
                  <artifactId>managed-plugin</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>test.group</groupId>
                      <artifactId>managed-dependency</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </plugin>
              </plugins>
            </pluginManagement>
          </build>
        </project>
        """;

    @TempDir
    Path tempDir;

    @Test
    void writesUpdatedVersionsPreservingFormatting() throws Exception {
        Path pomFile = tempDir.resolve("pom.xml");
        Files.writeString(pomFile, POM);
        Model model = MavenUtil.readModel(pomFile);
        model.setVersion("2.0.0");
        model.addProperty("other", "changed");
        Plugin managedPlugin =
                model.getBuild().getPluginManagement().getPlugins().get(0);
        managedPlugin.setVersion("2.0.0");
        managedPlugin.getDependencies().get(0).setVersion("2.0.0");

        Path written = PomXmlWriter.write(model, pomFile);

        assertThat(written).hasFileName(Constants.GITVER_POM_FILENAME);
        String xml = Files.readString(written);
        assertThat(xml)
                .contains("<!-- the version -->")
                .contains("<version>2.0.0</version>")
                .contains("<other>changed</other>")
                .doesNotContain("<revision>")
                .doesNotContain("1.0.0");
    }
}
