package org.emergent.gittle.maven;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY;
import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;
import static org.emergent.gittle.maven.GitRefType.COMMIT;
import static org.emergent.gittle.maven.MavenUtil.memoize;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jspecify.annotations.NullUnmarked;

@Getter
@NullUnmarked
@ToString
@EqualsAndHashCode
@JsonInclude(NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JacksonXmlRootElement(localName = "configuration")
public class Configuration {

  static final Supplier<Configuration> DEFAULT_INSTANCE =
      memoize(
          Throwing.wrap(
              () -> {
                String name = "schema/gittle-defaults.xml";
                ClassLoader loader = ProcessorUtil.class.getClassLoader();
                return ProcessorUtil.readConfig(Objects.requireNonNull(loader.getResource(name)));
              }));

  private static final String MATCH_ALL = ".*";

  public Boolean disable = false;

  @JsonDeserialize(using = IgnoreWhitespaceDeserializer.class)
  public String projectVersionPattern = null;

  @JsonDeserialize(using = IgnoreWhitespaceDeserializer.class)
  public String describeTagPattern = null;

  public Boolean describeTagFirstParent = true;

  public Boolean updatePom = false;

  public Boolean useRevision;

  public RefPatchDescriptionList refs = new RefPatchDescriptionList();

  public PatchDescription rev;

  @JsonInclude(NON_EMPTY)
  @JacksonXmlElementWrapper
  @JsonProperty(required = true)
  public List<RelatedProject> relatedProjects = new ArrayList<>();

  // @JsonIgnore
  // public boolean isUseRevision() {
  //   return Boolean.TRUE.equals(useRevision);
  // }

  public Pattern projectVersionPattern() {
    return Optional.ofNullable(projectVersionPattern).map(Pattern::compile).orElse(null);
  }

  public Pattern describeTagPattern() {
    return Optional.ofNullable(describeTagPattern).map(Pattern::compile).orElse(null);
  }

  @Getter
  @ToString
  @EqualsAndHashCode
  @JsonInclude(NON_NULL)
  public static class PatchDescription {

    public PatchDescription() {}

    public PatchDescription(PatchDescription that) {
      this.describeTagPattern = that.describeTagPattern;
      this.version = that.version;
      this.properties = new HashMap<>(that.properties);
      this.userProperties = new HashMap<>(that.userProperties);
      this.updatePom = that.updatePom;
      this.useRevision = that.useRevision;
      this.describeTagFirstParent = that.describeTagFirstParent;
    }

    @JsonDeserialize(using = IgnoreWhitespaceDeserializer.class)
    public String describeTagPattern;

    @JsonDeserialize(using = IgnoreWhitespaceDeserializer.class)
    public String version;

    @JsonInclude(NON_EMPTY)
    @JacksonXmlElementWrapper(useWrapping = false)
    // TODO  @JsonDeserialize(using = IgnoreWhitespaceDeserializer.class)
    public Map<String, String> properties = new HashMap<>();

    @JsonInclude(NON_EMPTY)
    @JacksonXmlElementWrapper(useWrapping = false)
    // TODO  @JsonDeserialize(using = IgnoreWhitespaceDeserializer.class)
    public Map<String, String> userProperties = new HashMap<>();

    public Boolean updatePom = false;

    public Boolean useRevision;

    public Boolean describeTagFirstParent;

    //
    // @JsonIgnore
    // public boolean isUseRevision() {
    //   return Boolean.TRUE.equals(useRevision);
    // }

    public Pattern describeTagPattern() {
      return Optional.ofNullable(describeTagPattern).map(Pattern::compile).orElse(null);
    }
  }

  @Getter
  @JsonInclude(NON_NULL)
  @ToString(callSuper = true)
  @EqualsAndHashCode(callSuper = true)
  public static class RefPatchDescription extends PatchDescription {

    @JacksonXmlProperty(isAttribute = true)
    public GitRefType type = COMMIT;

    @JsonDeserialize(using = IgnoreWhitespaceDeserializer.class)
    public String pattern;

    public RefPatchDescription() {}

    public RefPatchDescription(GitRefType type, Pattern pattern, PatchDescription description) {
      super(description);
      this.type = type;
      this.pattern = pattern != null ? pattern.pattern() : null;
    }

    public Pattern pattern() {
      return Optional.ofNullable(pattern).map(Pattern::compile).orElse(null);
    }
  }

  @Getter
  @ToString
  @EqualsAndHashCode
  public static class RefPatchDescriptionList {

    @JacksonXmlProperty(isAttribute = true)
    public Boolean considerTagsOnBranches = false;

    @JsonInclude(NON_EMPTY)
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "ref")
    public List<RefPatchDescription> list = new ArrayList<>();
  }

  @Getter
  @ToString
  @EqualsAndHashCode
  public static class RelatedProject {

    @JsonDeserialize(using = IgnoreWhitespaceDeserializer.class)
    public String groupId;

    @JsonDeserialize(using = IgnoreWhitespaceDeserializer.class)
    public String artifactId;

    @JsonCreator
    public RelatedProject(
        @JsonProperty(required = true, value = "groupId") String groupId,
        @JsonProperty(required = true, value = "artifactId") String artifactId) {
      this.groupId = groupId;
      this.artifactId = artifactId;
    }
  }

  public static class IgnoreWhitespaceDeserializer extends JsonDeserializer<Object> {
    @Override
    public Object deserialize(JsonParser jp, DeserializationContext context) throws IOException {
      return jp.getText().replaceAll("\\s+", "");
    }
  }
}
