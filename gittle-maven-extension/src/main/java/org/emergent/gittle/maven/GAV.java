package org.emergent.gittle.maven;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.Value;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.ReportPlugin;
import org.apache.maven.shared.utils.StringUtils;
import org.jspecify.annotations.Nullable;

/** Maven artifact identifier consisting of groupId, artifactId and version. */
@Value
// @AllArgsConstructor(access = AccessLevel.PACKAGE)
@Builder(toBuilder = true, builderClassName = "Builder", setterPrefix = "set")
class GAV implements Comparable<GAV> {

  private static final Comparator<@Nullable String> nullsLast =
      Comparator.nullsLast(Comparator.naturalOrder());

  private static final Comparator<GAV> COMPARATOR =
      Comparator.comparing(GAV::getGroupId, nullsLast)
          .thenComparing(GAV::getArtifactId, nullsLast)
          .thenComparing(GAV::getVersion, nullsLast);

  String groupId;
  String artifactId;
  String version;

  GAV(@Nullable String groupId, @Nullable String artifactId, @Nullable String version) {
    this.groupId = PropsUtil.nonNullOrEmpty(groupId);
    this.artifactId = PropsUtil.nonNullOrEmpty(artifactId);
    this.version = PropsUtil.nonNullOrEmpty(version);
  }

  public String getProjectId() {
    return sansVersion().toString();
  }

  public GAV sansVersion() {
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

  public static GAV of(
      @Nullable String groupId, @Nullable String artifactId, @Nullable String version) {
    return GAV.builder().setGroupId(groupId).setArtifactId(artifactId).setVersion(version).build();
  }

  public static GAV of(Model model) {
    String groupId = model.getGroupId();
    String artifactId = model.getArtifactId();
    String version = model.getVersion();
    Parent parent = model.getParent();
    if (parent != null) {
      if (groupId == null) {
        groupId = parent.getGroupId();
      }
      if (version == null) {
        version = parent.getVersion();
      }
    }
    return of(groupId, artifactId, version);
  }

  public static GAV of(Parent parent) {
    String groupId = parent.getGroupId();
    String artifactId = parent.getArtifactId();
    String version = parent.getVersion();
    return of(groupId, artifactId, version);
  }

  public static GAV of(Dependency dependency) {
    String groupId = dependency.getGroupId();
    String artifactId = dependency.getArtifactId();
    String version = dependency.getVersion();
    return of(groupId, artifactId, version);
  }

  public static GAV of(Plugin plugin) {
    String groupId = plugin.getGroupId();
    String artifactId = plugin.getArtifactId();
    String version = plugin.getVersion();
    return of(groupId, artifactId, version);
  }

  public static GAV of(ReportPlugin plugin) {
    String groupId = plugin.getGroupId();
    String artifactId = plugin.getArtifactId();
    String version = plugin.getVersion();
    return of(groupId, artifactId, version);
  }

  public static boolean equalsIgnoreVersion(GAV g1, GAV g2) {
    return Objects.equals(g1.sansVersion(), g2.sansVersion());
  }

  @Override
  public int compareTo(GAV o) {
    return COMPARATOR.compare(this, o);
  }

  @SuppressWarnings("unused")
  public static class Builder {

    // public Builder setVersion(@Nullable String version) {
    //   this.version = Optional.ofNullable(version).orElse("");
    //   return this;
    // }

    public GAV build() {
      return new GAV(
          Optional.ofNullable(groupId).filter(StringUtils::isNotEmpty).orElse(null),
          Optional.ofNullable(artifactId).filter(StringUtils::isNotEmpty).orElse(null),
          Optional.ofNullable(version).filter(StringUtils::isNotEmpty).orElse(null));
    }

    @Override
    public String toString() {
      return build().toString();
    }
  }
}
