package org.emergent.gitver.maven;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY;
import static com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS;
import static java.lang.Boolean.TRUE;
import static java.util.Objects.requireNonNull;
import static org.emergent.gitver.maven.GitRefType.COMMIT;
import static org.emergent.gitver.maven.MavenUtil.memoize;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jspecify.annotations.NullUnmarked;

@Getter
@NullUnmarked
@ToString
@EqualsAndHashCode(callSuper = true, doNotUseGetters = true)
@JsonInclude(NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
@JacksonXmlRootElement(localName = "configuration")
// @AllArgsConstructor(access = AccessLevel.PUBLIC, onConstructor_={@JsonCreator})
public class Configuration extends PatchDescription {

  private static final PatchDescription DEFAULT_DESC =
      new PatchDescription(
          "${commit}",
          "v(?<ver>.*)",
          true,
          false,
          true,
          new LinkedHashMap<>(),
          new LinkedHashMap<>());

  private static final Supplier<Configuration> DEFAULT_SUPPLIER =
      memoize(
          Throwing.wrap(
              () -> {
                String name = "schema/gitver-defaults.xml";
                ClassLoader loader = ProcessorUtil.class.getClassLoader();
                return ProcessorUtil.readConfig(requireNonNull(loader.getResource(name)));
              }));

  public static Configuration getDefault() {
    return DEFAULT_SUPPLIER.get();
  }

  public static Configuration read(URL resource) throws IOException {
    try (InputStream is = resource.openStream()) {
      return read(new String(ByteStreams.toByteArray(is), StandardCharsets.UTF_8));
    }
  }

  public static Configuration read(String content) throws IOException {
    SimpleModule module = new SimpleModule("gitver", new Version(1, 0, 0, "", null, null));
    module.addDeserializer(String.class, new TrimWhitespaceDeserializer());
    Configuration config = read(content, Configuration.class);
    Stream.concat(Stream.of(config.rev), config.refs.stream())
        .filter(Objects::nonNull)
        .forEach(pd -> pd.merge(config));
    return config;
  }

  public static <T> T read(String content, Class<? extends T> clazz) throws IOException {
    Version moduleVersion = new Version(1, 0, 0, "", null, null);
    SimpleModule module = new SimpleModule("gitver", moduleVersion);
    module.addDeserializer(String.class, new TrimWhitespaceDeserializer());
    XmlMapper mapper =
        XmlMapper.builder().enable(ACCEPT_CASE_INSENSITIVE_ENUMS).addModule(module).build();
    return mapper.readValue(content, clazz);
  }

  private Boolean disable;
  @Getter private String projectVersionPattern;
  private Boolean considerTagsOnBranches;

  public boolean getDisable() {
    return TRUE.equals(disable);
  }

  public boolean getConsiderTagsOnBranches() {
    return TRUE.equals(considerTagsOnBranches);
  }

  //
  // @JacksonXmlElementWrapper(useWrapping = false)
  // PatchDescription defaults = new PatchDescription(
  //    "${commit}", "v(?<ver>.*)",
  //        true, false, true,
  //    new LinkedHashMap<>(), new LinkedHashMap<>());

  @JacksonXmlElementWrapper(localName = "refs")
  @JacksonXmlProperty(localName = "ref")
  protected List<RefPatchDescription> refs = new ArrayList<>();

  protected PatchDescription rev;

  @JacksonXmlElementWrapper(localName = "relatedProjects")
  @JacksonXmlProperty(localName = "projects")
  private List<RelatedProject> relatedProjects = new ArrayList<>();

  public Configuration() {
    // this.refs = requireNonNullElseGet(refs, ArrayList::new);
    // this.relatedProjects = requireNonNullElseGet(relatedProjects, ArrayList::new);
    this.merge(DEFAULT_DESC);
    // Stream.concat(Stream.of(rev), refs.stream())
    //    .filter(Objects::nonNull)
    //    .forEach(pd -> pd.merge(this));
  }

  @Getter
  @ToString(callSuper = true)
  @EqualsAndHashCode(callSuper = true)
  @JsonInclude(NON_EMPTY)
  public static class RefPatchDescription extends PatchDescription {

    public GitRefType type = COMMIT;
    public String pattern;

    public RefPatchDescription() {}

    public RefPatchDescription(GitRefType type, Pattern pattern, PatchDescription description) {
      super(description);
      this.type = type;
      this.pattern = pattern != null ? pattern.pattern() : null;
    }
  }

  @Getter
  @ToString
  @EqualsAndHashCode
  @AllArgsConstructor
  @JsonInclude(NON_EMPTY)
  public static class RelatedProject {
    @JsonProperty(required = true)
    public String groupId;

    @JsonProperty(required = true)
    public String artifactId;
  }

  public static class TrimWhitespaceDeserializer extends JsonDeserializer<String> {
    @Override
    public String deserialize(JsonParser jp, DeserializationContext context) throws IOException {
      return jp.getText().trim();
    }
  }
}
