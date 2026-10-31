package org.emergent.gitver.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.jgit.lib.Constants.MASTER;

import java.io.File;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelProcessor;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One processor instance serving several sessions, as in a long-lived process such as mvnd. */
class GitverModelProcessorTest {

  private static final String POM =
      """
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>test.group</groupId>
        <artifactId>project</artifactId>
        <version>0.0.1-SNAPSHOT</version>
      </project>
      """;

  @TempDir Path tempDir;

  private Git git;
  private File pomFile;
  private final GitverSessionHolder sessionHolder = new GitverSessionHolder();
  private GitverModelProcessor processor;

  @BeforeEach
  void setUp() throws Exception {
    pomFile = tempDir.resolve("pom.xml").toFile();
    Files.writeString(pomFile.toPath(), POM);
    Files.writeString(tempDir.resolve(".gitignore"), Constants.GITVER_POM_NAME + "\n");
    Files.createDirectory(tempDir.resolve(".mvn"));
    Files.writeString(tempDir.resolve(".mvn/maven.config"), "");

    git = Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();
    git.add().addFilepattern(".").call();
    git.commit().setMessage("init").call();
    git.tag().setName("v1.0.0").call();

    processor = new GitverModelProcessor(sessionHolder, List.of(new PomReader()));
  }

  @Test
  void eachSessionSeesTheCurrentGitState() throws Exception {
    sessionHolder.start(session());
    assertThat(read().getVersion()).isEqualTo("1.0.0");
    sessionHolder.end();

    git.commit().setMessage("next").setAllowEmpty(true).call();

    sessionHolder.start(session());
    assertThat(read().getVersion()).isEqualTo("1.0.1-SNAPSHOT");
    sessionHolder.end();
  }

  @Test
  void modelReadOutsideSessionIsUnchangedAndDoesNotDisableLaterSessions() throws Exception {
    assertThat(read().getVersion()).isEqualTo("0.0.1-SNAPSHOT");

    sessionHolder.start(session());
    assertThat(read().getVersion()).isEqualTo("1.0.0");
    sessionHolder.end();
  }

  private Model read() throws Exception {
    return processor.read(pomFile, Map.of(ModelProcessor.SOURCE, new FileModelSource(pomFile)));
  }

  private static GitverSession session() {
    return new GitverSession(Instant.now(), new DefaultMavenExecutionRequest());
  }

  /** Stands in for maven's default processor. */
  private static final class PomReader implements ModelProcessor {

    @Override
    public File locatePom(File projectDirectory) {
      return new File(projectDirectory, "pom.xml");
    }

    @Override
    public Model read(File input, Map<String, ?> options) {
      return MavenUtil.readModel(input);
    }

    @Override
    public Model read(Reader input, Map<String, ?> options) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Model read(InputStream input, Map<String, ?> options) {
      throw new UnsupportedOperationException();
    }
  }
}
