package org.emergent.gittle.maven;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.building.ModelProcessor;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Maven goal plugin to set project POM file path to the git versioned POM file. NOTE: Utilized by
 * {@link ModelProcessor}
 */
@Mojo(name = GitVersioningMojo.GOAL, defaultPhase = LifecyclePhase.INITIALIZE, threadSafe = true)
public class GitVersioningMojo extends AbstractMojo {

  static final String GOAL = "git-versioning";

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Override
  public synchronized void execute() {
    getLog().debug("mojo run");
    File gitVersionedPomFile = new File(project.getBasedir(), Constants.GIT_VERSIONING_POM_NAME);
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
    Coords coords = BuildProperties.projectGAV();
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
}
