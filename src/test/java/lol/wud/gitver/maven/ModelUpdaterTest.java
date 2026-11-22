package lol.wud.gitver.maven;

import static lol.wud.gitver.TestGit.MASTER;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import lol.wud.gitver.TestGit;
import lol.wud.gitver.git.GitSituation;
import lol.wud.gitver.git.GitSituationFactory;
import lol.wud.gitver.util.VersionFormatter;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelUpdaterTest {

    private static final String GROUP = "test.group";

    @TempDir
    Path tempDir;

    private String head;
    private GitSituation situation;

    @BeforeEach
    void setUp() throws Exception {
        Path relatedDir = Files.createDirectories(tempDir.resolve("related"));
        Files.writeString(relatedDir.resolve(Constants.POM_XML), """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId>
              <artifactId>related</artifactId>
              <version>1.0.0</version>
            </project>\
            """.formatted(GROUP));
        TestGit git = TestGit.init(tempDir);
        git.addAll();
        head = git.commit("init");
        git.tag("v2.0.0");
        situation = java.util.Objects.requireNonNull(
                new GitSituationFactory().create(tempDir.resolve("pom.xml").toFile()));
    }

    @Test
    void updatesProjectAndRelatedDependencyVersions() throws Exception {
        Model model = model();
        model.addDependency(dependency("related"));
        model.addDependency(dependency("unrelated"));

        updater().update(model);

        assertThat(model.getVersion()).isEqualTo("2.0.0");
        assertThat(model.getDependencies()).extracting(Dependency::getVersion).containsExactly("2.0.0", "1.0.0");
    }

    @Test
    void updatesEachPluginDependencyOnce() throws Exception {
        Plugin plugin = plugin(dependency("related"));
        Plugin managedPlugin = plugin(dependency("related"));
        PluginManagement pluginManagement = new PluginManagement();
        pluginManagement.addPlugin(managedPlugin);
        Build build = new Build();
        build.addPlugin(plugin);
        build.setPluginManagement(pluginManagement);
        Model model = model();
        model.setBuild(build);

        updater().update(model);

        assertThat(plugin.getDependencies().get(0).getVersion()).isEqualTo("2.0.0");
        assertThat(managedPlugin.getDependencies().get(0).getVersion()).isEqualTo("2.0.0");
    }

    @Disabled
    @Test
    void addsGitPropertiesWithoutOverwriting() throws Exception {
        Model model = model();
        model.addProperty("git.ref", "mine");

        updater().update(model);

        assertThat(model.getProperties())
                .containsEntry("git.commit", head)
                .containsEntry("git.commit.short", head.substring(0, 7))
                .containsEntry("git.ref", "mine")
                .containsEntry("git.ref.slug", MASTER)
                .containsEntry("git.worktree", tempDir.toFile().getAbsolutePath());
    }

    private ModelUpdater updater() throws Exception {
        GitverSession session = new GitverSession(Instant.now(), new DefaultMavenExecutionRequest());
        Path mvnDir =  tempDir.resolve(".mvn");
        RelatedProjects related = RelatedProjects.create(model(), mvnDir, situation.getRootDirectory());
        return new ModelUpdater(new VersionFormatter(situation), related, situation, session);
    }

    private Model model() {
        Model model = new Model();
        model.setGroupId(GROUP);
        model.setArtifactId("project");
        model.setVersion("1.0.0");
        model.addModule("related");
        model.setPomFile(new File(tempDir.toFile(), Constants.POM_XML));
        return model;
    }

    private static Dependency dependency(String artifactId) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(GROUP);
        dependency.setArtifactId(artifactId);
        dependency.setVersion("1.0.0");
        return dependency;
    }

    private static Plugin plugin(Dependency dependency) {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.example");
        plugin.setArtifactId("some-plugin");
        plugin.setVersion("3.0");
        plugin.addDependency(dependency);
        return plugin;
    }
}
