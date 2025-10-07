package lol.wud.gitver.maven;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

@Mojo(name = UpdateAttachedMojo.GOAL, defaultPhase = LifecyclePhase.INITIALIZE, threadSafe = true)
public class UpdateAttachedMojo extends AbstractMojo {

  static final String GOAL = "update-attached";

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Override
  public synchronized void execute() {
    getLog().debug("updating attached pom");
    File gitVersionedPomFile = new File(project.getBasedir(), Constants.GITVER_POM_NAME);
    if (gitVersionedPomFile.isFile()) {
      project.setPomFile(gitVersionedPomFile);
      String pluginKey = asPlugin().getKey();
      Stream.of(project.getModel(), project.getOriginalModel())
          .map(Model::getBuild)
          .filter(Objects::nonNull)
          .map(Build::getPluginsAsMap)
          .forEach(pluginMap -> pluginMap.remove(pluginKey));
    }
  }

  static Plugin asPlugin() {
    Coords coords = MavenUtil.gitverCoords();
    Plugin plugin = new Plugin();
    plugin.setGroupId(coords.getGroupId());
    plugin.setArtifactId(coords.getArtifactId());
    plugin.setVersion(coords.getVersion());
    return plugin;
  }

  static Plugin asPluginWithExecution() {
    Plugin plugin = asPlugin();
    PluginExecution execution = new PluginExecution();
    execution.setId(GOAL);
    execution.setGoals(List.of(GOAL));
    plugin.addExecution(execution);
    return plugin;
  }

  /**
   * Adds this goal to the model's build, so the attached pom is switched to the git versioned pom
   * once maven runs the project.
   */
  static void install(Model projectModel) {
    Plugin gitVersioningPlugin = asPluginWithExecution();
    if (projectModel.getBuild() == null) {
      projectModel.setBuild(new Build());
    }

    // add at index 0 to be executed before any other project plugin,
    // to prevent malfunctions with other plugins
    projectModel.getBuild().getPlugins().add(0, gitVersioningPlugin);

    // exclude this plugin from maven enforce plugin
    List<Plugin> plugins = new ArrayList<>(projectModel.getBuild().getPlugins());
    if (projectModel.getBuild().getPluginManagement() != null) {
      plugins.addAll(projectModel.getBuild().getPluginManagement().getPlugins());
    }
    plugins.stream()
        .filter(it -> it.getKey().equals("org.apache.maven.plugins:maven-enforcer-plugin"))
        .forEach(
            plugin ->
                plugin
                    .getExecutions()
                    .forEach(
                        execution -> {
                          Optional.ofNullable((Xpp3Dom) execution.getConfiguration())
                              .map(configuration -> configuration.getChild("rules"))
                              .map(rules -> rules.getChild("requirePluginVersions"))
                              .ifPresent(
                                  requirePluginVersions -> {
                                    Xpp3Dom unCheckedPluginList =
                                        requirePluginVersions.getChild("unCheckedPluginList");
                                    if (unCheckedPluginList == null) {
                                      unCheckedPluginList = new Xpp3Dom("unCheckedPluginList");
                                      requirePluginVersions.addChild(unCheckedPluginList);
                                    }
                                    // prepend git versioning plugin to unCheckedPluginList
                                    unCheckedPluginList.setValue(
                                        gitVersioningPlugin.getKey()
                                            + ","
                                            + Objects.requireNonNullElse(
                                                unCheckedPluginList.getValue(), ""));
                                  });
                        }));
  }
}
