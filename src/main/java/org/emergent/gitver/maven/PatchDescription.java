package org.emergent.gitver.maven;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY;
import static java.util.Objects.requireNonNullElse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import java.util.HashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

@Getter
@ToString
@EqualsAndHashCode
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@JsonInclude(NON_EMPTY)
public class PatchDescription {

  public PatchDescription() {}

  public PatchDescription(PatchDescription that) {
    this(
        that.version,
        that.tagPattern,
        that.updateAttachedPom,
        that.updateSourcePom,
        that.followFirstParentOnly,
        new HashMap<>(that.properties),
        new HashMap<>(that.userProperties));
  }

  @Nullable public String version;
  @Nullable public String tagPattern;
  @Nullable public Boolean updateAttachedPom;
  @Nullable public Boolean updateSourcePom;
  @Nullable public Boolean followFirstParentOnly;

  @JacksonXmlElementWrapper(useWrapping = false)
  public Map<String, String> properties = new HashMap<>();

  @JacksonXmlElementWrapper(useWrapping = false)
  public Map<String, String> userProperties = new HashMap<>();

  protected void merge(PatchDescription that) {
    version = requireNonNullElse(version, that.version);
    tagPattern = requireNonNullElse(tagPattern, that.tagPattern);
    updateAttachedPom = requireNonNullElse(updateAttachedPom, that.updateAttachedPom);
    updateSourcePom = requireNonNullElse(updateSourcePom, that.updateSourcePom);
    followFirstParentOnly = requireNonNullElse(followFirstParentOnly, that.followFirstParentOnly);
    if (properties.isEmpty()) {
      properties.putAll(that.properties);
    }
    if (userProperties.isEmpty()) {
      userProperties.putAll(that.userProperties);
    }
  }
}
