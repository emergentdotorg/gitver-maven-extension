package lol.wud.gitver.maven;

import static lol.wud.gitver.maven.MavenUtil.getBuild;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import lol.wud.gitver.git.GitSituation;
import lol.wud.gitver.util.StringUtil;
import lol.wud.gitver.util.VersionFormatter;
import lol.wud.gitver.util.VersionUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.ModelBase;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginContainer;
import org.apache.maven.model.ReportPlugin;

/**
 * Applies calculated versions and git properties to the in-memory maven {@link Model}. Versions are
 * computed by the {@link VersionFormatter}; nothing here touches the file system.
 */
@Slf4j
final class ModelUpdater {

    private final VersionFormatter formatter;
    private final RelatedProjects related;
    private final GitSituation gitSituation;
    private final GitverSession session;

    ModelUpdater(
            VersionFormatter formatter, RelatedProjects related, GitSituation gitSituation, GitverSession session) {
        this.formatter = formatter;
        this.related = related;
        this.gitSituation = gitSituation;
        this.session = session;
    }

    /** Rewrites versions and adds git properties to the in-memory model. */
    void update(Model projectModel) {
        Coords coords = session.resolveRevision(projectModel);
        log.info("Updating version {} -> {}", coords, formatter.formatVersion(coords.getVersion()));

        updateParentVersion(projectModel);
        setFormattedVer(projectModel::setVersion, coords);
        updateDependencyVersions(projectModel);
        updatePluginVersions(projectModel);
        addGitProperties(projectModel);
        updateProfiles(projectModel);
    }

    private void addGitProperties(Model projectModel) {
        Properties properties = projectModel.getProperties();
        ZonedDateTime commitTime = gitSituation.getTimestamp();
        String refName = gitSituation.refName();
        properties.putIfAbsent("git.worktree", gitSituation.directory());
        properties.putIfAbsent("git.commit", gitSituation.commit());
        properties.putIfAbsent("git.commit.short", gitSituation.shortCommit());
        properties.putIfAbsent("git.commit.timestamp", String.valueOf(commitTime.toEpochSecond()));
        properties.putIfAbsent("git.commit.timestamp.datetime", VersionUtil.isoTimestamp(commitTime));
        properties.putIfAbsent("git.ref", refName);
        properties.putIfAbsent("git.ref.slug", VersionUtil.slugify(refName));
    }

    private void updateProfiles(Model model) {
        model.getProfiles().forEach(profile -> {
            updateDependencyVersions(profile);
            updatePluginVersions(profile);
        });
    }

    private void updateParentVersion(Model projectModel) {
        if (projectModel.getParent() != null) {
            setFormattedVer(projectModel.getParent()::setVersion, Coords.of(projectModel.getParent()));
        }
    }

    private void updateDependencyVersions(ModelBase model) {
        updateVersions("dependencies", model, model.getDependencies());
        Optional.ofNullable(model.getDependencyManagement())
                .ifPresent(dependencyManagement ->
                        updateVersions("dependency management", model, dependencyManagement.getDependencies()));
    }

    private void updatePluginVersions(ModelBase model) {
        Optional<BuildBase> build = Optional.ofNullable(getBuild(model));
        build.map(BuildBase::getPlugins).ifPresent(list -> updatePlugins("plugins", model, list));
        build.map(BuildBase::getPluginManagement)
                .map(PluginContainer::getPlugins)
                .ifPresent(list -> updatePlugins("plugin management", model, list));
        Optional.ofNullable(model.getReporting())
                .ifPresent(reporting -> updateVersions(
                        "reporting plugins", model, reporting.getPlugins(), Coords::of, ReportPlugin::setVersion));
    }

    private void updatePlugins(String section, ModelBase model, List<Plugin> plugins) {
        updateVersions(section, model, plugins, Coords::of, Plugin::setVersion);
        List<Dependency> dependencies = plugins.stream()
                .flatMap(plugin -> plugin.getDependencies().stream())
                .toList();
        updateVersions(section + " dependencies", model, dependencies);
    }

    private void updateVersions(String section, ModelBase model, List<Dependency> dependencies) {
        updateVersions(section, model, dependencies, Coords::of, Dependency::setVersion);
    }

    /** Sets the formatted version of each element that belongs to a related project. */
    private <T> void updateVersions(
            String section,
            ModelBase model,
            List<T> elements,
            Function<T, Coords> coordsOf,
            BiConsumer<T, String> versionSetter) {
        List<T> els = elements.stream()
                .filter(it -> related.contains(coordsOf.apply(it)))
                .toList();
        if (els.isEmpty()) {
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug(MavenUtil.section(section, model));
        }
        els.forEach(el -> setFormattedVer(v -> versionSetter.accept(el, v), coordsOf.apply(el)));
    }

    private void setFormattedVer(Consumer<String> setter, Coords coords) {
        String version = coords.getVersion();
        if (StringUtil.isNotBlank(version)) {
            String resolved = session.resolveRevision(version);
            String gitVersion = formatter.formatVersion(resolved);
            log.debug("{}: set version to {}", coords.getProjectId(), gitVersion);
            setter.accept(gitVersion);
        }
    }
}
