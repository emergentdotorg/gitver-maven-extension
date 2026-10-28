package org.emergent.gitver.maven;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder.Default;
import lombok.Value;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.ReportPlugin;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.utils.StringUtils;
import org.jspecify.annotations.Nullable;

@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@lombok.Builder(toBuilder = true, setterPrefix = "set")
public class Coords implements Comparable<Coords> {

  private static final Comparator<Coords> COMPARATOR =
      Comparator.comparing(Coords::getGroupId)
          .thenComparing(Coords::getArtifactId)
          .thenComparing(coords -> new ComparableVersion(coords.getVersion()));

  @Default String groupId = "";
  @Default String artifactId = "";
  @Default String version = "";

  public String getProjectId() {
    return sansVersion().toString();
  }

  public Coords sansVersion() {
    return StringUtils.isEmpty(version) ? this : toBuilder().setVersion(null).build();
  }

  public String getKey() {
    return sansVersion().toString();
  }

  @Override
  public String toString() {
    return Stream.of(groupId, artifactId, version)
        .filter(StringUtils::isNotEmpty)
        .collect(Collectors.joining(":"));
  }

  public static Coords of(
      @Nullable String groupId, @Nullable String artifactId, @Nullable String version) {
    return Coords.builder()
        .setGroupId(groupId)
        .setArtifactId(artifactId)
        .setVersion(version)
        .build();
  }

  public static Coords of(Model model) {
    Optional<Parent> parent = Optional.ofNullable(model.getParent());
    String groupId =
        Optional.ofNullable(model.getGroupId())
            .orElseGet(() -> parent.map(Parent::getGroupId).orElse(null));
    String version =
        Optional.ofNullable(model.getVersion())
            .orElseGet(() -> parent.map(Parent::getVersion).orElse(null));
    return of(groupId, model.getArtifactId(), version);
  }

  public static Coords of(Parent parent) {
    return of(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
  }

  public static Coords of(Dependency dependency) {
    return of(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
  }

  public static Coords of(Plugin plugin) {
    return of(plugin.getGroupId(), plugin.getArtifactId(), plugin.getVersion());
  }

  public static Coords of(ReportPlugin plugin) {
    return of(plugin.getGroupId(), plugin.getArtifactId(), plugin.getVersion());
  }

  public static Coords of(MavenProject project) {
    return of(project.getGroupId(), project.getArtifactId(), project.getVersion());
  }

  public static boolean equalsIgnoreVersion(Coords g1, Coords g2) {
    return Objects.equals(g1.sansVersion(), g2.sansVersion());
  }

  @Override
  public int compareTo(Coords o) {
    return COMPARATOR.compare(this, o);
  }

  @SuppressWarnings("unused")
  public static class Builder {

    public Builder setGroupId(@Nullable String groupId) {
      this.groupId$value = nonNullOrEmpty(groupId);
      this.groupId$set = true;
      return this;
    }

    public Builder setArtifactId(@Nullable String artifactId) {
      this.artifactId$value = nonNullOrEmpty(artifactId);
      this.artifactId$set = true;
      return this;
    }

    public Builder setVersion(@Nullable String version) {
      this.version$value = nonNullOrEmpty(version);
      this.version$set = true;
      return this;
    }

    @Override
    public String toString() {
      return build().toString();
    }

    private static String nonNullOrEmpty(@Nullable String groupId) {
      return Objects.requireNonNullElse(groupId, "");
    }
  }
}
