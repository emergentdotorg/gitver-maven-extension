package org.emergent.gitver.maven;

import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.emergent.gitver.git.GitRefType;
import org.emergent.gitver.maven.Configuration.RefConfig;
import org.emergent.gitver.util.GitverException;
import org.emergent.gitver.util.StringUtil;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

final class ConfigurationXmlReader {

  public static Configuration read(URL resource) {
    try (InputStream is = resource.openStream()) {
      return read(new String(ByteStreams.toByteArray(is), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new GitverException("Failed to read configuration " + resource, e);
    }
  }

  public static Configuration read(String content) {
    return read(content, null);
  }

  static Configuration read(String content, @Nullable Configuration defs) {
    return readFrom(parse(content).getDocumentElement(), defs);
  }

  private static Document parse(String content) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setExpandEntityReferences(false);
      return factory.newDocumentBuilder().parse(new InputSource(new StringReader(content)));
    } catch (IOException | ParserConfigurationException | SAXException e) {
      throw new GitverException("Failed to parse configuration: " + e.getMessage(), e);
    }
  }

  private static Configuration readFrom(Element element, @Nullable Configuration defs) {
    Configuration.Builder builder = Configuration.builder();

    Optional.ofNullable(childText(element, "disable"))
        .map(Boolean::valueOf)
        .ifPresent(builder::setDisable);
    Optional.ofNullable(childText(element, "projectVersionPattern"))
        .ifPresent(builder::setProjectVersionPattern);
    Optional.ofNullable(childText(element, "considerTagsOnBranches"))
        .map(Boolean::valueOf)
        .ifPresent(builder::setConsiderTagsOnBranches);

    RefConfig topConfig = readFallback(element);
    builder.setTopConfig(topConfig);

    Optional.ofNullable(child(element, "refs"))
        .ifPresent(
            re -> {
              children(re, "ref").stream()
                  .map(element1 -> readRefConfig(element1, topConfig))
                  .forEach(builder::setRef);

              boolean appendDefaults =
                  Optional.of(re.getAttribute("appendDefaults"))
                      .filter(StringUtil::isNotEmpty)
                      .map(Boolean::valueOf)
                      .orElse(false);

              if (defs != null && appendDefaults) {
                defs.getRefs().forEach(builder::setRef);
              }
            });

    Optional.ofNullable(child(element, "relatedProjects"))
        .map(re -> children(re, "project"))
        .stream()
        .flatMap(List::stream)
        .map(ConfigurationXmlReader::readRelatedProject)
        .forEach(builder::setRelatedProject);

    return builder.build();
  }

  private static RefConfig readFallback(Element element) {
    RefConfig options = readRefConfigBuilder(element, RefConfig.getDefault()).build();
    // The only time a fallback isn't passed in is when the configuration element is reading the
    // set of fallback options that get passed to all the revs and refs later.
    if (!options.getProperties().isEmpty() || !options.getUserProperties().isEmpty()) {
      throw new IllegalArgumentException("The configuration does not accept default properties");
    }
    return options;
  }

  private static RefConfig readRefConfig(Element element, RefConfig fallback) {
    RefConfig.Builder builder = readRefConfigBuilder(element, fallback);
    GitRefType refType =
        Optional.ofNullable(childText(element, "type"))
            .map(t -> t.toUpperCase(Locale.ROOT))
            .map(GitRefType::valueOf)
            .orElse(GitRefType.COMMIT);
    builder.setType(refType);
    if (refType != GitRefType.COMMIT) {
      String pattern =
          Optional.ofNullable(childText(element, "pattern"))
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "A ref of type %s requires a 'pattern' element be defined."
                              .formatted(refType)));
      builder.setPattern(pattern);
    }
    return builder.build();
  }

  private static RefConfig.Builder readRefConfigBuilder(Element element, RefConfig fallback) {
    RefConfig.Builder builder = RefConfig.builder();
    Optional.ofNullable(childText(element, "version"))
        .or(fallback::getVersion)
        .ifPresent(builder::setVersion);
    builder.setTagPattern(
        Optional.ofNullable(childText(element, "tagPattern")).orElse(fallback.getTagPattern()));
    builder.setUpdateAttachedPom(
        Optional.ofNullable(childText(element, "updateAttachedPom"))
            .map(Boolean::valueOf)
            .orElse(fallback.isUpdateAttachedPom()));
    builder.setUpdateSourcePom(
        Optional.ofNullable(childText(element, "updateSourcePom"))
            .map(Boolean::valueOf)
            .orElse(fallback.isUpdateSourcePom()));
    builder.setFollowFirstParentOnly(
        Optional.ofNullable(childText(element, "followFirstParentOnly"))
            .map(Boolean::valueOf)
            .orElse(fallback.isFollowFirstParentOnly()));
    builder.setProperties(
        Optional.ofNullable(child(element, "properties"))
            .map(ConfigurationXmlReader::childTextMap)
            .orElse(fallback.getProperties()));
    builder.setUserProperties(
        Optional.ofNullable(child(element, "userProperties"))
            .map(ConfigurationXmlReader::childTextMap)
            .orElse(fallback.getUserProperties()));
    return builder;
  }

  private static Coords readRelatedProject(Element element) {
    return Coords.builder()
        .setGroupId(childText(element, "groupId"))
        .setArtifactId(childText(element, "artifactId"))
        .setVersion("*")
        .build();
  }

  private static List<Element> children(Element parent, String name) {
    List<Element> result = new ArrayList<>();
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e && name.equals(nameOf(e))) {
        result.add(e);
      }
    }
    return result;
  }

  private static @Nullable Element child(Element parent, String name) {
    List<Element> found = children(parent, name);
    return found.isEmpty() ? null : found.get(0);
  }

  private static @Nullable String childText(Element parent, String name) {
    Element child = child(parent, name);
    return child == null ? null : child.getTextContent().trim();
  }

  private static Map<String, String> childTextMap(Element parent) {
    Map<String, String> result = new LinkedHashMap<>();
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e) {
        result.put(nameOf(e), e.getTextContent().trim());
      }
    }
    return result;
  }

  private static String nameOf(Element e) {
    return e.getLocalName() != null ? e.getLocalName() : e.getNodeName();
  }
}
