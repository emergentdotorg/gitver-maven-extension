package lol.wud.gitver.maven;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lol.wud.gitver.util.StringUtil;
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

@Mojo(name = UpdatePomMojo.GOAL, defaultPhase = LifecyclePhase.INITIALIZE, threadSafe = true)
public class UpdatePomMojo extends AbstractMojo {

    static final String GOAL = "update-pom";

    private static final String ENFORCER_KEY = "org.apache.maven.plugins:maven-enforcer-plugin";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public synchronized void execute() {
        getLog().debug("updating pom");
        File versionedPomFile = new File(project.getBasedir(), Constants.GITVER_POM_FILENAME);
        if (versionedPomFile.isFile()) {
            project.setPomFile(versionedPomFile);
            String pluginKey = MavenUtil.gitverCoords().getProjectId();
            Stream.of(project.getModel(), project.getOriginalModel())
                    .map(Model::getBuild)
                    .filter(Objects::nonNull)
                    .map(Build::getPluginsAsMap)
                    .forEach(pluginMap -> pluginMap.remove(pluginKey));
        }
    }

    /** Adds this mojo to the model so the versioned pom becomes the attached pom at execution. */
    static void addToBuild(Model projectModel) {
        Plugin gitverPlugin = asPluginWithExecution();

        Build build = projectModel.getBuild();
        if (build == null) {
            build = new Build();
            projectModel.setBuild(build);
        }

        // Add at index 0 to be executed before any other project plugin, to prevent malfunctions with other plugins
        build.getPlugins().add(0, gitverPlugin);

        // Exclude this plugin from maven enforce plugin
        configureEnforcerPlugin(build, gitverPlugin.getKey());
    }

    static Plugin asPluginWithExecution() {
        Coords coords = MavenUtil.gitverCoords();
        Plugin plugin = new Plugin();
        plugin.setGroupId(coords.getGroupId());
        plugin.setArtifactId(coords.getArtifactId());
        plugin.setVersion(coords.getVersion());
        PluginExecution execution = new PluginExecution();
        execution.setId(GOAL);
        execution.setGoals(List.of(GOAL));
        plugin.addExecution(execution);
        return plugin;
    }

    private static void configureEnforcerPlugin(Build build, String gitverPluginKey) {
        Stream.of(build, build.getPluginManagement())
                .filter(Objects::nonNull)
                .flatMap(pluginContainer -> pluginContainer.getPlugins().stream())
                .filter(plugin -> ENFORCER_KEY.equals(plugin.getKey()))
                .flatMap(plugin -> plugin.getExecutions().stream())
                .map(PluginExecution::getConfiguration)
                .filter(Xpp3Dom.class::isInstance)
                .map(Xpp3Dom.class::cast)
                .map(configuration -> configuration.getChild("rules"))
                .map(rules -> rules.getChild("requirePluginVersions"))
                .filter(Objects::nonNull)
                .forEach(requirePluginVersionsNode -> {
                    Xpp3Dom uncheckedNode = requirePluginVersionsNode.getChild("unCheckedPluginList");
                    if (uncheckedNode == null) {
                        uncheckedNode = new Xpp3Dom("unCheckedPluginList");
                        requirePluginVersionsNode.addChild(uncheckedNode);
                    }
                    String updatedUncheckedValue = Stream.of(gitverPluginKey, uncheckedNode.getValue())
                            .filter(StringUtil::isNotBlank)
                            .collect(Collectors.joining(","));
                    uncheckedNode.setValue(updatedUncheckedValue);
                });
    }
}
