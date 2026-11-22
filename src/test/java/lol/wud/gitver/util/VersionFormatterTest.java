package lol.wud.gitver.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import lol.wud.gitver.TestGit;
import lol.wud.gitver.git.GitSituationFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionFormatterTest {

    @TempDir
    Path tempDir;

    private TestGit git;

    @BeforeEach
    void setUp() throws Exception {
        git = TestGit.init(tempDir);
    }

    @Test
    void keepsProjectVersionWithoutTag() throws Exception {
        commit("init");

        assertThat(format("0.0.1-SNAPSHOT")).isEqualTo("0.0.1-SNAPSHOT");
    }

    @Test
    void onTag_usesTagVersion() throws Exception {
        commit("init");
        tag("v1.2.3");

        assertThat(format("0.0.1-SNAPSHOT")).isEqualTo("1.2.3");
    }

    @Test
    void onTag_ignoresLabelAndFillsMissingParts() throws Exception {
        commit("init");
        tag("v1.2-rc1");

        assertThat(format("0.0.1-SNAPSHOT")).isEqualTo("1.2.0");
    }

    @Test
    void pastTag_incrementsPatchAndAddsSnapshot() throws Exception {
        commit("one");
        tag("v1.2.3");
        commit("two");

        assertThat(format("0.0.1-SNAPSHOT")).isEqualTo("1.2.4-SNAPSHOT");
    }

    @Test
    void dirtyOnTag_incrementsPatchAndAddsSnapshot() throws Exception {
        commit("one");
        tag("v1.2.3");
        Files.writeString(tempDir.resolve("untracked.txt"), "x");

        assertThat(format("0.0.1-SNAPSHOT")).isEqualTo("1.2.4-SNAPSHOT");
    }

    private String format(String projectVersion) {
        var situation = java.util.Objects.requireNonNull(
                new GitSituationFactory().create(tempDir.resolve("pom.xml").toFile()));
        return new VersionFormatter(situation).formatVersion(projectVersion);
    }

    private void commit(String message) throws Exception {
        git.commit(message);
    }

    private void tag(String name) throws Exception {
        git.tag(name);
    }
}
