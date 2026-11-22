package lol.wud.gitver.git;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import lol.wud.gitver.TestGit;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitSituationFactoryTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        TestGit.init(tempDir).commit("init");
    }

    @Test
    void create_outsideRepository() {
        File pomFile = new File(tempDir.getRoot().toFile(), "pom.xml");
        // the file system root is not inside a git repository
        assertThat(create(Map.of(), Map.of(), pomFile)).isNull();
    }

    @Test
    void create_fromRepository() throws Exception {
        GitSituation situation = create(Map.of(), Map.of());
        assertThat(situation.getRootDirectory().toPath().toRealPath()).isEqualTo(tempDir.toRealPath());
        assertThat(situation.getDescription().tag()).isEqualTo("root");
    }

    @Test
    void create_tagOverride() {
        GitSituation situation = create(Map.of(), Map.of("GIT_TAG", "v3.1.4"));
        assertThat(situation.getDescription().tag()).isEqualTo("v3.1.4");
        assertThat(situation.getDescription().distance()).isZero();
    }

    @Test
    void create_countsCommitsWithoutTag() throws Exception {
        TestGit.open(tempDir).commit("two");
        GitSituation situation = create(Map.of(), Map.of());
        assertThat(situation.getDescription().tag()).isEqualTo("root");
        assertThat(situation.getDescription().distance()).isEqualTo(2);
    }

    @Test
    void create_tagWithDashes() throws Exception {
        TestGit git = TestGit.init(tempDir.resolve("dashes"));
        git.commit("one");
        git.tag("release-1.2.3-rc-1");
        git.commit("two");
        GitSituation situation = requireNonNull(
                create(Map.of(), Map.of(), tempDir.resolve("dashes/pom.xml").toFile()));
        assertThat(situation.getDescription().tag()).isEqualTo("release-1.2.3-rc-1");
        assertThat(situation.getDescription().distance()).isEqualTo(1);
    }

    @Test
    void create_repositoryWithoutCommits() throws Exception {
        Path empty = Files.createDirectories(tempDir.resolve("empty"));
        TestGit.init(empty);
        GitSituation situation = requireNonNull(
                create(Map.of(), Map.of(), empty.resolve("pom.xml").toFile()));
        assertThat(situation.getDescription().tag()).isEqualTo("root");
        assertThat(situation.getDescription().distance()).isZero();
    }

    @Test
    void create_missingDirectory() {
        assertThat(create(Map.of(), Map.of(), tempDir.resolve("nope/pom.xml").toFile()))
                .isNull();
    }

    private GitSituation create(Map<String, String> userProperties, Map<String, String> env) {
        return requireNonNull(
                create(userProperties, env, tempDir.resolve("pom.xml").toFile()));
    }

    private static @Nullable GitSituation create(
            Map<String, String> userProperties, Map<String, String> env, File pomFile) {
        MavenExecutionRequest request = new DefaultMavenExecutionRequest();
        userProperties.forEach(request.getUserProperties()::setProperty);
        return new GitSituationFactory(env).create(pomFile);
    }
}
