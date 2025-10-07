package lol.wud.gitver.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.jgit.lib.Constants.MASTER;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import lol.wud.gitver.git.GitRefType;
import lol.wud.gitver.git.GitSituation;
import lol.wud.gitver.maven.Configuration.RefConfig;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelUpdaterTest {

  private static final String GROUP = "test.group";
  private static final Coords RELATED = Coords.of(GROUP, "related", "*");

  @TempDir Path tempDir;

  private RevCommit head;
  private GitSituation situation;

  @BeforeEach
  void setUp() throws Exception {
    Git git = Git.init().setInitialBranch(MASTER).setDirectory(tempDir.toFile()).call();
    head = git.commit().setMessage("init").setAllowEmpty(true).call();
    situation = GitSituation.find(tempDir.toFile()).orElseThrow();
  }

  @Test
  void updatesProjectAndRelatedDependencyVersions() throws Exception {
    Model model = model();
    model.addDependency(dependency("related"));
    model.addDependency(dependency("unrelated"));

    updater(Map.of()).update(model);

    assertThat(model.getVersion()).isEqualTo("1.0.0-git");
    assertThat(model.getDependencies())
        .extracting(Dependency::getVersion)
        .containsExactly("1.0.0-git", "1.0.0");
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

    updater(Map.of()).update(model);

    assertThat(plugin.getDependencies().get(0).getVersion()).isEqualTo("1.0.0-git");
    assertThat(managedPlugin.getDependencies().get(0).getVersion()).isEqualTo("1.0.0-git");
  }

  @Test
  void updatesConfiguredProperties() throws Exception {
    Model model = model();
    model.addProperty("foo", "1");

    updater(Map.of("foo", "${value}-bar", "absent", "x")).update(model);

    assertThat(model.getProperties()).containsEntry("foo", "1-bar").doesNotContainKey("absent");
  }

  @Test
  void addsGitPropertiesWithoutOverwriting() throws Exception {
    Model model = model();
    model.addProperty("git.ref", "mine");

    updater(Map.of()).update(model);

    assertThat(model.getProperties())
        .containsEntry("git.commit", head.getName())
        .containsEntry("git.commit.short", head.getName().substring(0, 7))
        .containsEntry("git.ref", "mine")
        .containsEntry("git.ref.slug", MASTER)
        .containsEntry("git.worktree", tempDir.toFile().getAbsolutePath());
  }

  private ModelUpdater updater(Map<String, String> properties) throws Exception {
    RefConfig ref =
        RefConfig.builder()
            .setType(GitRefType.BRANCH)
            .setPattern(".+")
            .setVersion("${version}-git")
            .setProperties(properties)
            .build();
    Configuration config = Configuration.builder().setRef(ref).setRelatedProject(RELATED).build();
    GitVersionDetails details = new GitVersionDetails(situation.getRev(), MASTER, ref);
    GitverSession session = new GitverSession(Instant.now(), new DefaultMavenExecutionRequest());
    VersionFormatter formatter = new VersionFormatter(situation, details, session, config);
    RelatedProjects related =
        RelatedProjects.determine(
            model(), config, new File(tempDir.toFile(), ".mvn"), situation.getRootDirectory());
    return new ModelUpdater(formatter, related, situation, details, session);
  }

  private Model model() {
    Model model = new Model();
    model.setGroupId(GROUP);
    model.setArtifactId("project");
    model.setVersion("1.0.0");
    model.setPomFile(new File(tempDir.toFile(), "pom.xml"));
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
