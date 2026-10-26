package org.emergent.gittle.maven.util;

import static java.util.Optional.ofNullable;
import static org.emergent.gittle.maven.util.PomHelper.Marks.CHILD_START;
import static org.emergent.gittle.maven.util.PomHelper.Marks.END;
import static org.emergent.gittle.maven.util.PomHelper.Marks.END_ELEMENT;
import static org.emergent.gittle.maven.util.PomHelper.Marks.PARENT_START;
import static org.emergent.gittle.maven.util.PomHelper.Marks.START;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import lombok.Getter;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.OverConstrainedVersionException;
import org.apache.maven.artifact.versioning.Restriction;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.model.ReportPlugin;
import org.apache.maven.model.Reporting;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.codehaus.mojo.versions.api.AbstractVersionDetails;
import org.codehaus.mojo.versions.api.ArtifactAssociation;
import org.codehaus.mojo.versions.api.ArtifactVersions;
import org.codehaus.mojo.versions.api.Property;
import org.codehaus.mojo.versions.api.ResolverAdapter;
import org.codehaus.mojo.versions.api.Segment;
import org.codehaus.mojo.versions.api.VersionRetrievalException;
import org.codehaus.mojo.versions.ordering.BoundArtifactVersion;
import org.codehaus.mojo.versions.ordering.InvalidSegmentException;
import org.codehaus.mojo.versions.rewriting.MutableXMLStreamReader;
import org.codehaus.mojo.versions.utils.ArtifactFactory;
import org.codehaus.mojo.versions.utils.ArtifactVersionService;
import org.codehaus.mojo.versions.utils.ModelNode;
import org.codehaus.mojo.versions.utils.RegexUtils;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.codehaus.stax2.XMLInputFactory2;
import org.jspecify.annotations.Nullable;

/** A utility class for parsing and performing operations on pom.xml files */
public class PomHelper {

  /** Static instance of the {@code org.apache.maven.plugins} string */
  public static final String APACHE_MAVEN_PLUGINS_GROUPID = "org.apache.maven.plugins";

  private static final Set<String> IMPLICIT_PATHS =
      new HashSet<>(
          Arrays.asList(
              "/project/parent/groupId", "/project/parent/artifactId",
              "/project/parent/version", "/project/groupId",
              "/project/artifactId", "/project/version"));

  private static final Pattern PATTERN_PROJECT_VERSION = Pattern.compile("/project/version");

  private static final Pattern PATTERN_PROJECT_PARENT_VERSION =
      Pattern.compile("/project/parent/version");

  private static final Pattern PATTERN_PROJECT_DEPENDENCY =
      Pattern.compile(
          "/project"
              + "(/profiles/profile)?"
              + "((/dependencyManagement)|(/build(/pluginManagement)?/plugins/plugin))?"
              + "/dependencies/dependency");

  private static final Pattern PATTERN_PROJECT_DEPENDENCY_VERSION =
      Pattern.compile(
          "/project"
              + "(/profiles/profile)?"
              + "((/dependencyManagement)|(/build(/pluginManagement)?/plugins/plugin))?"
              + "/dependencies/dependency"
              + "((/groupId)|(/artifactId)|(/version))");

  private static final Pattern PATTERN_PROJECT_PLUGIN =
      Pattern.compile(
          "/project"
              + "(/profiles/profile)?"
              + "((/build(/pluginManagement)?)|(/reporting))/plugins/plugin");

  private static final Pattern PATTERN_PROJECT_PLUGIN_VERSION =
      Pattern.compile(
          "/project"
              + "(/profiles/profile)?"
              + "((/build(/pluginManagement)?)|(/reporting))/plugins/plugin"
              + "((/groupId)|(/artifactId)|(/version))");

  private final ArtifactFactory artifactFactory;

  private final ExpressionEvaluator expressionEvaluator;

  enum Marks {
    CHILD_START,
    PARENT_START,
    END_ELEMENT,
    START,
    END
  }

  /**
   * Creates a new instance
   *
   * @param artifactFactory {@link ArtifactFactory} instance
   * @param expressionEvaluator {@link ExpressionEvaluator} instance
   */
  public PomHelper(ArtifactFactory artifactFactory, ExpressionEvaluator expressionEvaluator) {
    this.artifactFactory = artifactFactory;
    this.expressionEvaluator = expressionEvaluator;
  }

  /**
   * Gets the raw model before any interpolation what-so-ever.
   *
   * @param project The project to getModel the raw model for.
   * @return The raw model.
   * @throws IOException if the file is not found or if the file does not parse.
   */
  public static Model getRawModel(MavenProject project) throws IOException {
    return getRawModel(project.getFile());
  }

  /**
   * Gets the raw model before any interpolation what-so-ever.
   *
   * @param moduleProjectFile The project file to getModel the raw model for.
   * @return The raw model.
   * @throws IOException if the file is not found or if the file does not parse.
   */
  public static Model getRawModel(File moduleProjectFile) throws IOException {
    try (Reader reader =
        new BufferedReader(
            new InputStreamReader(Files.newInputStream(moduleProjectFile.toPath())))) {
      Model result = getRawModel(reader);
      result.setPomFile(moduleProjectFile);
      return result;
    }
  }

  /**
   * Gets the current raw model before any interpolation what-so-ever.
   *
   * @param modelString a string containing the raw model
   * @param modelPath the File containing the model
   * @return The raw model.
   * @throws IOException if the file is not found or if the file does not parse.
   */
  public static Model getRawModel(String modelString, File modelPath) throws IOException {
    try (Reader reader = new StringReader(modelString)) {
      Model result = getRawModel(reader);
      result.setPomFile(modelPath);
      return result;
    }
  }

  /**
   * Gets the current raw model before any interpolation what-so-ever.
   *
   * @param reader The {@link Reader} to getModel the raw model for.
   * @return The raw model.
   * @throws IOException if the file is not found or if the file does not parse.
   */
  public static Model getRawModel(Reader reader) throws IOException {
    try {
      return new MavenXpp3Reader().read(reader);
    } catch (XmlPullParserException e) {
      throw new IOException(e.getMessage(), e);
    }
  }

  /**
   * Searches the pom re-defining the specified property to the specified version.
   *
   * @param pom The pom to modify.
   * @param profileId The profile in which to modify the property.
   * @param property The property to modify.
   * @param value The new value of the property.
   * @return <code>true</code> if a replacement was made.
   * @throws XMLStreamException if somethinh went wrong.
   */
  public static boolean setPropertyVersion(
      MutableXMLStreamReader pom, @Nullable String profileId, String property, String value)
      throws XMLStreamException {
    class PropertyVersionInternal {
      final Pattern propertyRegex =
          Pattern.compile(
              (profileId == null ? "/project/properties/" : "/project/profiles/profile/properties/")
                  + RegexUtils.quote(property));
      final Pattern matchScopeRegex =
          profileId == null
              ? Pattern.compile("/project/properties")
              : Pattern.compile("/project/profiles/profile");
      final Pattern profileIdRegex = Pattern.compile("/project/profiles/profile/id");

      boolean setPropertyVersion(String path, boolean inMatchScope) throws XMLStreamException {
        boolean replaced = false;
        while (!replaced && pom.hasNext()) {
          pom.next();
          if (pom.isStartElement()) {
            String elementPath = path + "/" + pom.getLocalName();
            if (propertyRegex.matcher(elementPath).matches()) {
              pom.mark(START);
            } else if (matchScopeRegex.matcher(elementPath).matches()) {
              // we're in a new match scope -> reset any previous partial matches
              inMatchScope = profileId == null;
              pom.clearMark(START);
              pom.clearMark(END);
            } else if (profileId != null && profileIdRegex.matcher(elementPath).matches()) {
              inMatchScope = profileId.trim().equals(pom.getElementText().trim());
            }

            // getElementText could've pushed the pointer to END_ELEMENT
            if (!pom.isEndElement()) {
              // inMatchScope will not change in the child element
              replaced = setPropertyVersion(elementPath, inMatchScope);
            }
          } else if (pom.isEndElement()) {
            if (propertyRegex.matcher(path).matches()) {
              pom.mark(END);
            } else if (matchScopeRegex.matcher(path).matches()) {
              if (inMatchScope && pom.hasMark(START) && pom.hasMark(END)) {
                pom.replaceBetween(START, END, value);
                replaced = true;
              }
              pom.clearMark(START);
              pom.clearMark(END);
            }
            return replaced;
          }
        }
        return replaced;
      }
    }

    pom.rewind();
    return new PropertyVersionInternal().setPropertyVersion("", false);
  }

  /**
   * Searches the pom re-defining the project version to the specified version.
   *
   * @param pom The pom to modify.
   * @param value The new value of the property.
   * @return <code>true</code> if a replacement was made.
   * @throws XMLStreamException if somethinh went wrong.
   */
  public static boolean setProjectVersion(MutableXMLStreamReader pom, String value)
      throws XMLStreamException {
    return setElementValue(pom, "/project", "version", value, false);
  }

  /**
   * Sets the value of the given element given its parent element path. Will only consider the first
   * found occurrence of the parent element. If the element is not found in the parent element, the
   * method will create the element.
   *
   * @param pom pom to modify
   * @param parentPath path of the parent element
   * @param elementName name of the element to set or create
   * @param value the new value of the element
   * @return {@code true} if the element was created or replaced
   * @throws XMLStreamException if something went wrong
   */
  public static boolean setElementValue(
      MutableXMLStreamReader pom, String parentPath, String elementName, String value)
      throws XMLStreamException {
    pom.rewind();
    return setElementValue(pom, parentPath, elementName, value, true);
  }

  /**
   * Sets the value of the given element given its parent element path. Will only consider the first
   * found occurrence of the parent element. If the element is not found in the parent element, the
   * method will create the element if {@code shouldCreate} is {@code true}.
   *
   * @param pom pom to modify
   * @param parentPath path of the parent element
   * @param elementName name of the element to set or create
   * @param value the new value of the element
   * @param shouldCreate should the element be created if it's not found in the first encountered
   *     parent element matching the parentPath
   * @return {@code true} if the element was created or replaced
   * @throws XMLStreamException if something went wrong
   */
  public static boolean setElementValue(
      MutableXMLStreamReader pom,
      String parentPath,
      String elementName,
      String value,
      boolean shouldCreate)
      throws XMLStreamException {
    class ElementValueInternal {
      private final String parentName;
      private final String superParentPath;

      ElementValueInternal() {
        int lastDelimeterIndex = parentPath.lastIndexOf('/');
        parentName = parentPath.substring(lastDelimeterIndex + 1);
        superParentPath = parentPath.substring(0, lastDelimeterIndex);
      }

      boolean process(String currentPath) throws XMLStreamException {
        boolean replacementMade = false;
        while (!replacementMade && pom.hasNext()) {
          pom.next();
          if (pom.isStartElement()) {
            // here, we will only mark the beginning of the child or the parent element
            if (currentPath.equals(parentPath) && elementName.equals(pom.getLocalName())) {
              pom.mark(CHILD_START);
            } else if (currentPath.equals(superParentPath)
                && pom.getLocalName().equals(parentName)) {
              pom.mark(PARENT_START);
            }
            // process child element
            replacementMade = process(currentPath + "/" + pom.getLocalName());
          } else if (pom.isEndElement()) {
            // here we're doing the replacement
            if (currentPath.equals(parentPath + "/" + elementName)) {
              // end of the child
              replaceValueInChild();
              replacementMade = true;
            } else if (shouldCreate && currentPath.equals(parentPath)) {
              // end of the parent
              replaceValueInParent();
              replacementMade = true;
            } else {
              return false;
            }
          }
        }
        return replacementMade;
      }

      private void replaceValueInChild() {
        pom.mark(END_ELEMENT);
        if (!pom.getBetween(CHILD_START, END_ELEMENT).isEmpty()) {
          pom.replaceBetween(CHILD_START, END_ELEMENT, value);
        } else {
          pom.replace(String.format("<%1$s>%2$s</%1$s>", elementName, value));
        }
      }

      private void replaceValueInParent() {
        pom.mark(END_ELEMENT);
        if (pom.hasMark(PARENT_START)) {
          if (!pom.getBetween(PARENT_START, END_ELEMENT).isEmpty()) {
            pom.replace(String.format("<%2$s>%3$s</%2$s></%1$s>", parentName, elementName, value));
          } else {
            pom.replace(
                String.format("<%1$s><%2$s>%3$s</%2$s></%1$s>", parentName, elementName, value));
          }
        } else {
          pom.replace(
              String.format("<%1$s><%2$s>%3$s</%2$s></%1$s>", parentName, elementName, value));
        }
      }
    }

    pom.rewind();
    return new ElementValueInternal().process("");
  }

  static <T> @Nullable T executeOnPatternFound(
      String path, final MutableXMLStreamReader pom, Pattern pattern, Supplier<T> onPatternFound)
      throws XMLStreamException {
    T result = null;
    while (result == null && pom.hasNext()) {
      pom.next();
      if (pom.isStartElement()) {
        String elementPath = path + "/" + pom.getLocalName();
        if (pattern.matcher(elementPath).matches()) {
          pom.mark(START);
        }
        result = executeOnPatternFound(elementPath, pom, pattern, onPatternFound);
      } else if (pom.isEndElement()) {
        if (pattern.matcher(path).matches()) {
          pom.mark(END);
          if (pom.hasMark(START)) {
            return onPatternFound.get();
          }
          pom.clearMark(START);
          pom.clearMark(END);
        }
        break;
      }
    }
    return result;
  }

  /**
   * Retrieves the project version from the pom.
   *
   * @param pom The pom.
   * @return the project version or <code>null</code> if the project version is not defined (i.e.
   *     inherited from parent version).
   * @throws XMLStreamException if something went wrong.
   */
  public static @Nullable String getProjectVersion(final MutableXMLStreamReader pom)
      throws XMLStreamException {
    pom.rewind();
    return executeOnPatternFound(
        "", pom, PATTERN_PROJECT_VERSION, () -> pom.getBetween(START, END).trim());
  }

  /**
   * Searches the pom re-defining the project version to the specified version.
   *
   * @param pom The pom to modify.
   * @param value The new value of the property.
   * @return <code>true</code> if a replacement was made.
   * @throws XMLStreamException if somethinh went wrong.
   */
  public static boolean setProjectParentVersion(
      final MutableXMLStreamReader pom, final String value) throws XMLStreamException {
    pom.rewind();
    return ofNullable(
            executeOnPatternFound(
                "",
                pom,
                PATTERN_PROJECT_PARENT_VERSION,
                () -> {
                  pom.replaceBetween(START, END, value);
                  return true;
                }))
        .orElse(false);
  }

  /**
   * Searches the pom re-defining the specified dependency to the specified version.
   *
   * @param pom The pom to modify.
   * @param groupId The groupId of the dependency.
   * @param artifactId The artifactId of the dependency.
   * @param oldVersion The old version of the dependency.
   * @param newVersion The new version of the dependency.
   * @param model The model to getModel the project properties from.
   * @param logger The logger to use.
   * @return <code>true</code> if a replacement was made.
   * @throws XMLStreamException if something went wrong.
   */
  @SuppressWarnings("checkstyle:MethodLength")
  public static boolean setDependencyVersion(
      final MutableXMLStreamReader pom,
      final String groupId,
      final String artifactId,
      final String oldVersion,
      final String newVersion,
      final Model model,
      final Log logger)
      throws XMLStreamException {
    Map<String, String> implicitProperties = getImplicitProperties(pom, model);

    String path = "";
    boolean inMatchScope = false;
    boolean madeReplacement = false;
    boolean haveGroupId = false;
    boolean haveArtifactId = false;
    boolean haveOldVersion = false;

    pom.rewind();
    for (Deque<String> stack = new ArrayDeque<>(); pom.hasNext(); ) {
      pom.next();
      if (pom.isStartElement()) {
        stack.push(path);
        path = path + "/" + pom.getLocalName();
        if (PATTERN_PROJECT_DEPENDENCY.matcher(path).matches()) {
          // we're in a new match scope
          // reset any previous partial matches
          inMatchScope = true;
          pom.clearMark(START);
          pom.clearMark(END);

          haveGroupId = false;
          haveArtifactId = false;
          haveOldVersion = false;
        } else if (inMatchScope && PATTERN_PROJECT_DEPENDENCY_VERSION.matcher(path).matches()) {
          if ("groupId".equals(pom.getLocalName())) {
            haveGroupId =
                groupId.equals(evaluate(pom.getElementText().trim(), implicitProperties, logger));
          } else if ("artifactId".equals(pom.getLocalName())) {
            haveArtifactId =
                artifactId.equals(
                    evaluate(pom.getElementText().trim(), implicitProperties, logger));
          } else if ("version".equals(pom.getLocalName())) {
            pom.mark(START);
          }
        }
      }
      // for empty elements, pom can be both start- and end element
      if (pom.isEndElement()) {
        if (PATTERN_PROJECT_DEPENDENCY_VERSION.matcher(path).matches()
            && "version".equals(pom.getLocalName())) {
          pom.mark(END);
          String compressedPomVersion =
              StringUtils.deleteWhitespace(pom.getBetween(START, END).trim());
          String compressedOldVersion = StringUtils.deleteWhitespace(oldVersion);

          try {
            haveOldVersion = isVersionOverlap(compressedOldVersion, compressedPomVersion);
          } catch (InvalidVersionSpecificationException e) {
            // fall back to string comparison
            haveOldVersion = compressedOldVersion.equals(compressedPomVersion);
          }
        } else if (PATTERN_PROJECT_DEPENDENCY.matcher(path).matches()) {
          if (inMatchScope
              && pom.hasMark(START)
              && pom.hasMark(END)
              && haveGroupId
              && haveArtifactId
              && haveOldVersion) {
            pom.replaceBetween(START, END, newVersion);
            madeReplacement = true;
          }
          pom.clearMark(START);
          pom.clearMark(END);
          haveArtifactId = false;
          haveGroupId = false;
          haveOldVersion = false;
          inMatchScope = false;
        }
        path = stack.pop();
      }
    }
    return madeReplacement;
  }

  static Map<String, String> getImplicitProperties(MutableXMLStreamReader pom, Model model)
      throws XMLStreamException {

    Map<String, String> implicitProperties =
        model.getProperties().entrySet().stream()
            .collect(Collectors.toMap(e -> (String) e.getKey(), e -> (String) e.getValue()));

    pom.rewind();
    getImplicitProperties("", pom, implicitProperties);

    List<Pair<String, String>> parentProperties =
        implicitProperties.entrySet().stream()
            .filter(e -> e.getKey().contains(".parent"))
            .map(e -> Pair.of(e.getKey().replace(".parent", ""), e.getValue()))
            .toList();
    parentProperties.forEach(p -> implicitProperties.putIfAbsent(p.getLeft(), p.getRight()));
    return implicitProperties;
  }

  private static void getImplicitProperties(
      String path, MutableXMLStreamReader pom, Map<String, String> acc) throws XMLStreamException {
    while (pom.hasNext()) {
      pom.next();
      if (pom.isStartElement()) {
        String elementPath = path + "/" + pom.getLocalName();
        if (IMPLICIT_PATHS.contains(elementPath)) {
          final String elementText = pom.getElementText().trim();
          acc.put(elementPath.substring(1).replace('/', '.'), elementText);
          // getElementText pushed the pointer to END_ELEMENT, we should NOT descend
        } else {
          // only descend if state != END_ELEMENT at this point
          getImplicitProperties(elementPath, pom, acc);
        }
      } else if (pom.isEndElement()) {
        return;
      }
    }
  }

  /**
   * A lightweight expression evaluation function.
   *
   * @param expr The expression to evaluate.
   * @param properties The properties to substitute.
   * @param logger The logger to use.
   * @return The evaluated expression.
   */
  public static @Nullable String evaluate(
      @Nullable String expr, Map<String, String> properties, Log logger) {
    if (expr == null) {
      return null;
    }

    return extractExpression(expr)
        .map(
            expression -> {
              String value = properties.get(expression);
              if (value != null) {
                int exprStartDelimiter = value.indexOf("${");
                if (exprStartDelimiter >= 0) {
                  value =
                      value.substring(0, exprStartDelimiter)
                          + evaluate(value.substring(exprStartDelimiter), properties, logger);
                }
              } else {
                // Because we work with the raw model, without interpolation, unevaluatable
                // expressions are not
                // unexpected
                logger.debug("expression: " + expression + " no value ");
              }
              return value == null ? expr : value;
            })
        .orElseGet(
            () -> {
              int index = expr.indexOf("${");
              if (index >= 0) {
                int lastIndex = expr.indexOf("}", index);
                if (lastIndex >= 0) {
                  String retVal =
                      expr.substring(0, index)
                          + evaluate(expr.substring(index, lastIndex + 1), properties, logger)
                          + evaluate(expr.substring(lastIndex + 1), properties, logger);
                  if (index > 0 && expr.charAt(index - 1) == '$') {
                    retVal = expr.substring(0, index - 1) + retVal;
                  }
                  return retVal;
                }
              }
              return expr.contains("$$") ? expr.replaceAll("\\$\\$", "\\$") : expr;
            });
  }

  /**
   * Strips the expression token markers from the start and end of the string.
   *
   * @param expr the string (perhaps with token markers)
   * @return the string (without token markers) if a property has been found, {@link
   *     Optional#empty()} otherwise
   */
  public static Optional<String> extractExpression(@Nullable String expr) {
    return ofNullable(expr)
        .map(String::trim)
        .filter(e -> e.startsWith("${") && e.indexOf("}") == e.length() - 1)
        .map(e -> e.substring(2, e.length() - 1));
  }

  /**
   * Checks if two versions or ranges have an overlap.
   *
   * @param leftVersionOrRange the 1st version number or range to test
   * @param rightVersionOrRange the 2nd version number or range to test
   * @return true if both versions have an overlap
   * @throws InvalidVersionSpecificationException if the versions can't be parsed to a range
   */
  static boolean isVersionOverlap(String leftVersionOrRange, String rightVersionOrRange)
      throws InvalidVersionSpecificationException {
    VersionRange pomVersionRange = createVersionRange(leftVersionOrRange);
    if (!pomVersionRange.hasRestrictions()) {
      return true;
    }

    VersionRange oldVersionRange = createVersionRange(rightVersionOrRange);
    if (!oldVersionRange.hasRestrictions()) {
      return true;
    }

    VersionRange result = oldVersionRange.restrict(pomVersionRange);
    return result.hasRestrictions();
  }

  private static VersionRange createVersionRange(String versionOrRange)
      throws InvalidVersionSpecificationException {
    VersionRange versionRange = VersionRange.createFromVersionSpec(versionOrRange);
    if (versionRange.getRecommendedVersion() != null) {
      versionRange = VersionRange.createFromVersionSpec("[" + versionOrRange + "]");
    }
    return versionRange;
  }

  /**
   * Searches the pom re-defining the specified plugin to the specified version.
   *
   * @param pom The pom to modify.
   * @param groupId The groupId of the dependency.
   * @param artifactId The artifactId of the dependency.
   * @param oldVersion The old version of the dependency.
   * @param newVersion The new version of the dependency.
   * @return <code>true</code> if a replacement was made.
   * @throws XMLStreamException if somethinh went wrong.
   */
  public static boolean setPluginVersion(
      MutableXMLStreamReader pom,
      @Nullable String groupId,
      String artifactId,
      String oldVersion,
      String newVersion)
      throws XMLStreamException {
    Deque<String> stack = new ArrayDeque<>();
    String path = "";
    boolean inMatchScope = false;
    boolean madeReplacement = false;
    boolean haveGroupId = false;
    boolean needGroupId = groupId != null && !APACHE_MAVEN_PLUGINS_GROUPID.equals(groupId);
    boolean haveArtifactId = false;
    boolean haveOldVersion = false;

    pom.rewind();
    while (pom.hasNext()) {
      pom.next();
      if (pom.isStartElement()) {
        stack.push(path);
        final String elementName = pom.getLocalName();
        path = path + "/" + elementName;

        if (PATTERN_PROJECT_PLUGIN.matcher(path).matches()) {
          // we're in a new match scope
          // reset any previous partial matches
          inMatchScope = true;
          pom.clearMark(START);
          pom.clearMark(END);

          haveGroupId = false;
          haveArtifactId = false;
          haveOldVersion = false;
        } else if (inMatchScope && PATTERN_PROJECT_PLUGIN_VERSION.matcher(path).matches()) {
          if ("groupId".equals(elementName)) {
            haveGroupId = pom.getElementText().trim().equals(groupId);
          } else if ("artifactId".equals(elementName)) {
            haveArtifactId = artifactId.equals(pom.getElementText().trim());
          } else if ("version".equals(elementName)) {
            pom.mark(START);
          }
        }
      }
      // for empty elements, pom can be both start- and end element
      if (pom.isEndElement()) {
        if (PATTERN_PROJECT_PLUGIN_VERSION.matcher(path).matches()
            && "version".equals(pom.getLocalName())) {
          pom.mark(END);

          try {
            haveOldVersion = isVersionOverlap(oldVersion, pom.getBetween(START, END).trim());
          } catch (InvalidVersionSpecificationException e) {
            // fall back to string comparison
            haveOldVersion = oldVersion.equals(pom.getBetween(START, END).trim());
          }
        } else if (PATTERN_PROJECT_PLUGIN.matcher(path).matches()) {
          if (inMatchScope
              && pom.hasMark(START)
              && pom.hasMark(END)
              && (haveGroupId || !needGroupId)
              && haveArtifactId
              && haveOldVersion) {
            pom.replaceBetween(START, END, newVersion);
            madeReplacement = true;
            pom.clearMark(START);
            pom.clearMark(END);
            haveArtifactId = false;
            haveGroupId = false;
            haveOldVersion = false;
          }
          inMatchScope = false;
        }
        path = stack.pop();
      }
    }
    return madeReplacement;
  }

  /**
   * Traverses the project tree upwards, adding the raw models of every project it encounters to the
   * map
   *
   * @param project maven project of the child for which the models need to be gathered
   * @return gathered map of raw models per project
   */
  static Map<MavenProject, Model> getRawModelWithParents(MavenProject project) throws IOException {
    // constructs a tree sorted from children to parents
    Map<MavenProject, Model> models =
        new TreeMap<>(
            (p1, p2) -> {
              for (MavenProject p = p1; p != null; p = p.getParent()) {
                if (p == p2) // meaning p2 is an ancestor to p1 or p1 == p2
                {
                  return p == p1 ? 0 : -1; // p1 is the child
                }
              }
              return 1;
            });
    for (MavenProject p = project; p != null; p = p.getParent()) {
      models.put(p, p.getFile() != null ? getRawModel(p) : p.getOriginalModel());
    }
    return models;
  }

  /**
   * Examines the project to find any properties which are associated with versions of artifacts in
   * the project.
   *
   * @param resolverAdapter {@link ResolverAdapter} instance
   * @param log {@link Log} instance
   * @param project The project to examine.
   * @param includeParent whether parent POMs should be included
   * @return An array of properties that are associated within the project.
   * @throws ExpressionEvaluationException if an expression cannot be evaluated.
   * @throws IOException if the project's pom file cannot be parsed.
   * @since 1.0-alpha-3
   */
  public PropertyVersionsBuilder[] getPropertyVersionsBuilders(
      ResolverAdapter resolverAdapter, Log log, MavenProject project, boolean includeParent)
      throws ExpressionEvaluationException, IOException {

    Map<MavenProject, Model> reactorModels =
        includeParent
            ? getRawModelWithParents(project)
            : Collections.singletonMap(project, getRawModel(project));

    Set<String> activeProfileIds =
        project.getActiveProfiles().stream().map(Profile::getId).collect(Collectors.toSet());

    Map<String, PropertyVersionsBuilder> propertiesMap = new TreeMap<>();
    reactorModels
        .values()
        .forEach(
            model -> {
              processProfiles(resolverAdapter, log, propertiesMap, model, activeProfileIds);
              putPropertiesIfAbsent(
                  resolverAdapter, log, propertiesMap, null, model.getProperties());
            });

    // Process the project and its parent hierarchy
    for (MavenProject currentProject = project;
        currentProject != null;
        currentProject = includeParent ? currentProject.getParent() : null) {

      Model model = reactorModels.get(currentProject);
      if (model != null) {
        processModel(propertiesMap, model, activeProfileIds);
      }
    }

    // Finally, remove any properties without associations
    propertiesMap.values().removeIf(versions -> !versions.isAssociated());

    return propertiesMap.values().toArray(new PropertyVersionsBuilder[0]);
  }

  private void processProfiles(
      ResolverAdapter resolverAdapter,
      Log log,
      Map<String, PropertyVersionsBuilder> propertiesMap,
      Model model,
      Set<String> activeProfileIds) {

    model.getProfiles().stream()
        .filter(profile -> activeProfileIds.contains(profile.getId()))
        .forEach(
            profile -> {
              try {
                putPropertiesIfAbsent(
                    resolverAdapter, log, propertiesMap, profile.getId(), profile.getProperties());
                processDependencies(
                    propertiesMap, profile.getDependencyManagement(), profile.getDependencies());
                processBuild(propertiesMap, profile.getBuild());
                processReporting(propertiesMap, profile.getReporting());
              } catch (ExpressionEvaluationException e) {
                throw new RuntimeException(e);
              }
            });
  }

  private void processModel(
      Map<String, PropertyVersionsBuilder> propertiesMap, Model model, Set<String> activeProfileIds)
      throws ExpressionEvaluationException {

    processDependencies(propertiesMap, model.getDependencyManagement(), model.getDependencies());
    processBuild(propertiesMap, model.getBuild());
    processReporting(propertiesMap, model.getReporting());

    // Process active profiles within the model
    for (Profile profile : model.getProfiles()) {
      if (activeProfileIds.contains(profile.getId())) {
        processDependencies(
            propertiesMap, profile.getDependencyManagement(), profile.getDependencies());
      }
    }
  }

  private void processDependencies(
      Map<String, PropertyVersionsBuilder> propertiesMap,
      @Nullable DependencyManagement depMgmt,
      List<Dependency> dependencies)
      throws ExpressionEvaluationException {
    if (depMgmt != null) {
      addDependencyAssocations(propertiesMap, depMgmt.getDependencies(), false);
    }
    addDependencyAssocations(propertiesMap, dependencies, false);
  }

  private void processBuild(
      Map<String, PropertyVersionsBuilder> propertiesMap, @Nullable BuildBase build)
      throws ExpressionEvaluationException {

    if (build != null) {
      if (build.getPluginManagement() != null) {
        addPluginAssociations(propertiesMap, build.getPluginManagement().getPlugins());
      }
      addPluginAssociations(propertiesMap, build.getPlugins());
    }
  }

  private void processReporting(
      Map<String, PropertyVersionsBuilder> propertiesMap, @Nullable Reporting reporting)
      throws ExpressionEvaluationException {

    if (reporting != null) {
      addReportPluginAssociations(propertiesMap, reporting.getPlugins());
    }
  }

  /**
   * Takes a list of {@link org.apache.maven.model.Plugin} instances and adds associations to
   * properties used to define versions of the plugin artifact or any of the plugin dependencies
   * specified in the pom.
   *
   * @param result The map of {@link org.codehaus.mojo.versions.api.PropertyVersionsBuilder} keyed
   *     by property name.
   * @param plugins The list of {@link org.apache.maven.model.Plugin}.
   * @throws ExpressionEvaluationException if an expression cannot be evaluated.
   */
  private void addPluginAssociations(
      Map<String, PropertyVersionsBuilder> result, @Nullable List<Plugin> plugins)
      throws ExpressionEvaluationException {
    if (plugins == null) {
      return;
    }
    for (Plugin plugin : plugins) {
      String version = plugin.getVersion();
      if (version != null && version.contains("${") && version.indexOf('}') != -1) {
        version = StringUtils.deleteWhitespace(version);
        for (PropertyVersionsBuilder property : result.values()) {
          // any of these could be defined by a property
          final String propertyRef = "${" + property.getName() + "}";
          if (version.contains(propertyRef)) {
            String groupId = plugin.getGroupId();
            if (StringUtils.isBlank(groupId)) {
              // groupId has a special default
              groupId = APACHE_MAVEN_PLUGINS_GROUPID;
            } else {
              groupId = (String) expressionEvaluator.evaluate(groupId);
            }
            String artifactId = plugin.getArtifactId();
            if (StringUtils.isBlank(artifactId)) {
              // malformed pom
              continue;
            } else {
              artifactId = (String) expressionEvaluator.evaluate(artifactId);
            }
            // might as well capture the current value
            String evaluatedVersion = (String) expressionEvaluator.evaluate(plugin.getVersion());
            property.withAssociation(
                artifactFactory.createMavenPluginArtifact(groupId, artifactId, evaluatedVersion),
                true);
            if (!propertyRef.equals(version)) {
              addBounds(property, version, propertyRef);
            }
          }
        }
      }
      addDependencyAssocations(result, plugin.getDependencies(), true);
    }
  }

  private void addReportPluginAssociations(
      Map<String, PropertyVersionsBuilder> result, @Nullable List<ReportPlugin> reportPlugins)
      throws ExpressionEvaluationException {
    if (reportPlugins == null) {
      return;
    }
    for (ReportPlugin plugin : reportPlugins) {
      String version = plugin.getVersion();
      if (version != null && version.contains("${") && version.indexOf('}') != -1) {
        version = StringUtils.deleteWhitespace(version);
        for (PropertyVersionsBuilder property : result.values()) {
          final String propertyRef = "${" + property.getName() + "}";
          if (version.contains(propertyRef)) {
            // any of these could be defined by a property
            String groupId = plugin.getGroupId();
            if (StringUtils.isBlank(groupId)) {
              // groupId has a special default
              groupId = APACHE_MAVEN_PLUGINS_GROUPID;
            } else {
              groupId = (String) expressionEvaluator.evaluate(groupId);
            }
            String artifactId = plugin.getArtifactId();
            if (StringUtils.isBlank(artifactId)) {
              // malformed pom
              continue;
            } else {
              artifactId = (String) expressionEvaluator.evaluate(artifactId);
            }
            // might as well capture the current value
            String versionEvaluated = (String) expressionEvaluator.evaluate(plugin.getVersion());
            property.withAssociation(
                artifactFactory.createMavenPluginArtifact(groupId, artifactId, versionEvaluated),
                true);
            if (!propertyRef.equals(version)) {
              addBounds(property, version, propertyRef);
            }
          }
        }
      }
    }
  }

  private void addDependencyAssocations(
      Map<String, PropertyVersionsBuilder> result,
      @Nullable List<Dependency> dependencies,
      boolean usePluginRepositories)
      throws ExpressionEvaluationException {
    if (dependencies == null) {
      return;
    }
    for (Dependency dependency : dependencies) {
      String version = dependency.getVersion();
      if (version != null && version.contains("${") && version.indexOf('}') != -1) {
        version = StringUtils.deleteWhitespace(version);
        for (PropertyVersionsBuilder property : result.values()) {
          final String propertyRef = "${" + property.getName() + "}";
          if (version.contains(propertyRef)) {
            // Any of these could be defined by a property
            String groupId = dependency.getGroupId();
            if (StringUtils.isBlank(groupId)) {
              // malformed pom
              continue;
            } else {
              groupId = (String) expressionEvaluator.evaluate(groupId);
            }
            String artifactId = dependency.getArtifactId();
            if (StringUtils.isBlank(artifactId)) {
              // malformed pom
              continue;
            } else {
              artifactId = (String) expressionEvaluator.evaluate(artifactId);
            }
            // might as well capture the current value
            String versionEvaluated =
                (String) expressionEvaluator.evaluate(dependency.getVersion());
            property.withAssociation(
                artifactFactory.createArtifact(
                    groupId,
                    artifactId,
                    versionEvaluated,
                    dependency.getType(),
                    dependency.getClassifier(),
                    dependency.getScope(),
                    dependency.isOptional()),
                usePluginRepositories);
            if (!propertyRef.equals(version)) {
              addBounds(property, version, propertyRef);
            }
          }
        }
      }
    }
  }

  private static void addBounds(
      PropertyVersionsBuilder builder, String rawVersionRange, String propertyRef) {
    Pattern lowerBound =
        Pattern.compile("([(\\[])([^,]*)," + RegexUtils.quote(propertyRef) + "([)\\]])");
    Pattern upperBound =
        Pattern.compile("([(\\[])" + RegexUtils.quote(propertyRef) + ",([^,]*)([)\\]])");
    Matcher m = lowerBound.matcher(rawVersionRange);
    if (m.find()) {
      boolean includeLower = "[".equals(m.group(1));
      String lowerLimit = m.group(2);
      if (StringUtils.isNotEmpty(lowerLimit)) {
        builder.withLowerBound(lowerLimit, includeLower);
      }
    }
    m = upperBound.matcher(rawVersionRange);
    if (m.find()) {
      boolean includeUpper = "[".equals(m.group(3));
      String upperLimit = m.group(2);
      if (StringUtils.isNotEmpty(upperLimit)) {
        builder.withUpperBound(upperLimit, includeUpper);
      }
    }
  }

  private void putPropertiesIfAbsent(
      ResolverAdapter resolverAdapter,
      Log log,
      Map<String, PropertyVersionsBuilder> result,
      @Nullable String profileId,
      @Nullable Properties properties) {
    ofNullable(properties)
        .map(Properties::stringPropertyNames)
        .ifPresent(
            propertyNames ->
                propertyNames.forEach(
                    propertyName ->
                        result.putIfAbsent(
                            propertyName,
                            new PropertyVersionsBuilder(
                                resolverAdapter, profileId, propertyName, log))));
  }

  /**
   * Returns a set of all child modules for a project, including any defined in profiles (ignoring
   * profile activation).
   *
   * @param project The project.
   * @param logger The logger to use.
   * @return the set of all child modules of the project.
   */
  public static Set<String> getAllChildModules(MavenProject project, Log logger) {
    return getAllChildModules(project.getOriginalModel(), logger);
  }

  /**
   * Returns a set of all child modules for a project, including any defined in profiles (ignoring
   * profile activation).
   *
   * @param model The project model.
   * @param logger The logger to use.
   * @return the set of all child modules of the project.
   */
  public static Set<String> getAllChildModules(Model model, Log logger) {
    logger.debug("Finding child modules of " + model);
    Set<String> childModules =
        Stream.concat(
                model.getModules().stream(),
                model.getProfiles().stream().flatMap(profile -> profile.getModules().stream()))
            .collect(TreeSet::new, Set::add, Set::addAll);
    debugModules(logger, "Child modules:", childModules);
    return childModules;
  }

  /**
   * Outputs a debug message with a list of modules.
   *
   * @param logger The logger to log to.
   * @param message The message to display.
   * @param modules The modules to append to the message.
   */
  public static void debugModules(Log logger, String message, Collection<String> modules) {
    if (logger.isDebugEnabled()) {
      logger.debug(message);
      if (modules.isEmpty()) {
        logger.debug("None.");
      } else {
        modules.forEach(module -> logger.debug("  " + module));
      }
    }
  }

  /**
   * Extracts the version from a raw model, interpolating from the parent if necessary.
   *
   * @param model The model.
   * @return The version.
   */
  public static @Nullable String getVersion(Model model) {
    return ofNullable(model.getVersion())
        .orElse(ofNullable(model.getParent()).map(Parent::getVersion).orElse(null));
  }

  /**
   * Checks to see if the model contains an explicitly specified version.
   *
   * @param model The model.
   * @return {@code true} if the model explicitly specifies the project version, i.e.
   *     /project/version
   */
  public static boolean isExplicitVersion(Model model) {
    return model.getVersion() != null;
  }

  /**
   * Extracts the artifactId from a raw model, interpolating from the parent if necessary.
   *
   * @param model The model.
   * @return The artifactId.
   */
  public static @Nullable String getArtifactId(Model model) {
    return ofNullable(model.getArtifactId())
        .orElse(ofNullable(model.getParent()).map(Parent::getArtifactId).orElse(null));
  }

  /**
   * Extracts the groupId from a raw model, interpolating from the parent if necessary.
   *
   * @param model The model.
   * @return The groupId.
   */
  public static @Nullable String getGroupId(Model model) {
    return ofNullable(model.getGroupId())
        .orElse(ofNullable(model.getParent()).map(Parent::getGroupId).orElse(null));
  }

  /**
   * Finds the local root of the current project of the {@link MavenSession} instance.
   *
   * @param projectBuilder {@link ProjectBuilder} instance
   * @param mavenSession {@link MavenSession} instance
   * @param logger The logger to log tog
   * @return The local root (note this may be the project passed as an argument).
   */
  public static MavenProject getLocalRoot(
      ProjectBuilder projectBuilder, MavenSession mavenSession, Log logger) {
    logger.info("Searching for local aggregator root...");
    MavenProject project = mavenSession.getCurrentProject();
    while (true) {
      final File parentDir = project.getBasedir().getParentFile();
      if (parentDir != null && parentDir.isDirectory()) {
        logger.debug("Checking to see if " + parentDir + " is an aggregator parent");
        File parentFile = new File(parentDir, "pom.xml");
        if (parentFile.isFile()) {
          try {
            ProjectBuildingResult result =
                projectBuilder.build(parentFile, createProjectBuilderRequest(mavenSession));
            if (!result.getProblems().isEmpty()) {
              logger.warn(
                  "Problems encountered during the computation of the local aggregation root.");
              result.getProblems().forEach(p -> logger.warn("\t" + p.getMessage()));
            }
            if (getAllChildModules(result.getProject(), logger)
                .contains(project.getBasedir().getName())) {
              logger.debug(parentDir + " is an aggregator parent");
              project = result.getProject();
              continue;
            } else {
              logger.debug(parentDir + " is not an aggregator parent");
            }
          } catch (ProjectBuildingException e) {
            logger.warn(e);
          }
        }
      }
      logger.debug("Local aggregation root is " + project.getBasedir());
      return project;
    }
  }

  /**
   * Convenience method for creating a {@link ProjectBuildingRequest} instance based on maven
   * session.
   *
   * <p><u>Note:</u> The method initializes the remote repositories with the remote artifact
   * repositories. Please use the initializers if you need to override this.
   *
   * @param mavenSession {@link MavenSession} instance
   * @param initializers optional additional initializers, which will be executed after the object
   *     is initialized
   * @return constructed builder request
   */
  @SafeVarargs
  public static ProjectBuildingRequest createProjectBuilderRequest(
      MavenSession mavenSession, Consumer<ProjectBuildingRequest>... initializers) {
    return new DefaultProjectBuildingRequest() {
      {
        setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
        setResolveDependencies(false);
        setLocalRepository(mavenSession.getLocalRepository());
        setRemoteRepositories(mavenSession.getCurrentProject().getRemoteArtifactRepositories());
        setBuildStartTime(mavenSession.getStartTime());
        setUserProperties(mavenSession.getUserProperties());
        setSystemProperties(mavenSession.getSystemProperties());
        setActiveProfileIds(mavenSession.getRequest().getActiveProfiles());
        setInactiveProfileIds(mavenSession.getRequest().getInactiveProfiles());
        setRepositorySession(mavenSession.getRepositorySession());
        Arrays.stream(initializers).forEach(i -> i.accept(this));
      }
    };
  }

  /**
   * Builds a {@link ModelNode} tree of raw models keyed by module path and returns a list of all
   * nodes, ordered depth-first visiting order. The root node is always the first node of the list.
   *
   * @param rootNode The root node of the reactor
   * @param logger logger to log parsing errors to
   * @return the root node of the {@link ModelNode} of raw models relative to the project's basedir.
   */
  public static List<ModelNode> getRawModelTree(ModelNode rootNode, Log logger)
      throws UncheckedIOException {
    Path baseDir = rootNode.getModel().getPomFile().getParentFile().toPath();
    List<ModelNode> result = new ArrayList<>();
    result.add(rootNode);
    result.addAll(
        getAllChildModules(rootNode.getModel(), logger).stream()
            .map(baseDir::resolve)
            .map(path -> Files.isDirectory(path) ? path.resolve("pom.xml") : path)
            .map(
                pomFile -> {
                  try {
                    MutableXMLStreamReader pom = new MutableXMLStreamReader(pomFile);
                    return new ModelNode(
                        rootNode, getRawModel(pom.getSource(), pomFile.toFile()), pom);
                  } catch (IOException e) {
                    throw new UncheckedIOException("Could not open " + pomFile, e);
                  } catch (XMLStreamException e) {
                    throw new RuntimeException("Could not parse " + pomFile, e);
                  }
                })
            .flatMap(node -> getRawModelTree(node, logger).stream())
            .toList());
    return result;
  }

  /**
   * Traverses the module tree upwards searching for the closest definition of a property with the
   * given name.
   *
   * @param propertyName name of the property to be found
   * @param node model tree node at which the search should be started
   * @return {@link Optional} object containing the model tree node containing the closest property
   *     definition, or {@link Optional#empty()} if none has been found
   */
  public static Optional<ModelNode> findProperty(String propertyName, ModelNode node) {
    if (ofNullable(node.getModel().getProperties())
        .map(properties -> properties.getProperty(propertyName))
        .isPresent()) {
      return Optional.of(node);
    }
    return node.getParent().flatMap(parent -> findProperty(propertyName, parent));
  }

  /**
   * Builds a map of raw models keyed by module path.
   *
   * @param project The project to build from.
   * @param logger The logger for logging.
   * @return A map of raw models keyed by path relative to the project's basedir.
   * @throws IOException if things go wrong.
   */
  public static Map<File, Model> getChildModels(MavenProject project, Log logger)
      throws IOException {
    Map<File, Model> result = new LinkedHashMap<>();
    final Model model = getRawModel(project);
    result.put(project.getFile(), model);
    result.putAll(getChildModels(model, logger));
    return result;
  }

  /**
   * Builds a sub-map of raw models keyed by module path.
   *
   * @param model The root model
   * @param logger The logger for logging.
   * @return A map of raw models keyed by path relative to the project's basedir.
   */
  private static Map<File, Model> getChildModels(Model model, Log logger) {
    Map<File, Model> result = new LinkedHashMap<>();
    Map<File, Model> childResults = new LinkedHashMap<>();

    File baseDir = model.getPomFile().getParentFile();

    getAllChildModules(model, logger).stream()
        .map(moduleName -> new File(baseDir, moduleName))
        .map(file -> file.isFile() ? file : new File(file, "pom.xml"))
        .filter(File::exists)
        .forEach(
            pomFile -> {
              try {
                // the aim of this goal is to fix problems when the project cannot be parsed by
                // Maven,
                // so we have to work with the raw model and not the interpolated parsed model from
                // maven
                Model moduleModel = getRawModel(pomFile);
                result.put(pomFile, moduleModel);
                childResults.putAll(getChildModels(moduleModel, logger));
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });

    result.putAll(
        childResults); // more efficient update order if all children are added after siblings
    return result;
  }

  /**
   * Returns all the models that have a specified groupId and artifactId as parent.
   *
   * @param reactor The map of models keyed by path.
   * @param groupId The groupId of the parent.
   * @param artifactId The artifactId of the parent.
   * @return a map of models that have a specified groupId and artifactId as parent keyed by path.
   */
  public static Map<File, Model> getChildModels(
      Map<File, Model> reactor, String groupId, String artifactId) {
    final Map<File, Model> result = new LinkedHashMap<>();
    for (Map.Entry<File, Model> entry : reactor.entrySet()) {
      final File path = entry.getKey();
      final Model model = entry.getValue();
      final Parent parent = model.getParent();
      if (parent != null
          && groupId.equals(parent.getGroupId())
          && artifactId.equals(parent.getArtifactId())) {
        result.put(path, model);
      }
    }
    return result;
  }

  /**
   * Returns the model that has the specified groupId and artifactId or <code>null</code> if no such
   * model exists.
   *
   * @param reactor The map of models keyed by path.
   * @param groupId The groupId to match.
   * @param artifactId The artifactId to match.
   * @return The model or <code>null</code> if the model was not in the reactor.
   */
  public static @Nullable Model getModel(
      Map<File, Model> reactor, @Nullable String groupId, String artifactId) {
    return reactor.values().stream()
        .filter(
            model ->
                (groupId == null || groupId.equals(getGroupId(model)))
                    && artifactId.equals(getArtifactId(model)))
        .findAny()
        .orElse(null);
  }

  /**
   * Returns the model that has the specified groupId (if specified) and artifactId or <code>null
   * </code> if no such model exists.
   *
   * @param reactor The map of models keyed by path.
   * @param groupId The groupId to match.
   * @param artifactId The artifactId to match.
   * @return The model entry or <code>null</code> if the model was not in the reactor.
   */
  public static Map.@Nullable Entry<File, Model> getModelEntry(
      Map<File, Model> reactor, @Nullable String groupId, String artifactId) {
    return reactor.entrySet().stream()
        .filter(
            e ->
                (groupId == null || groupId.equals(getGroupId(e.getValue())))
                    && artifactId.equals(getArtifactId(e.getValue())))
        .findAny()
        .orElse(null);
  }

  /**
   * Returns a count of how many parents a model has in the reactor.
   *
   * @param reactor The map of models keyed by path.
   * @param model The model.
   * @return The number of parents of this model in the reactor.
   */
  public static int getReactorParentCount(Map<File, Model> reactor, Model model) {
    if (model.getParent() == null) {
      return 0;
    }
    Model parentModel =
        getModel(reactor, model.getParent().getGroupId(), model.getParent().getArtifactId());
    if (parentModel == null) {
      return 0;
    }
    return getReactorParentCount(reactor, parentModel) + 1;
  }

  /**
   * Reads an XML from the given InputStream.
   *
   * @param inputStream The input stream to read.
   * @return a pair representing the (mutable) content of the file with the charset of the file
   * @throws IOException thrown if it's not possible to read from the provided input stream
   * @throws XMLStreamException thrown if there are problems parsing the provided input stream
   */
  public static Pair<String, Charset> readXml(InputStream inputStream)
      throws IOException, XMLStreamException {
    try (BufferedInputStream buffer = new BufferedInputStream(inputStream)) {
      // XMLStreamReader is only used to discover the encoding
      // NB. We can't use a Transformer because XMLStreamReader will override
      // the preamble with its own detected encoding, which is not always what the original file has

      buffer.mark(0x4000);
      XMLInputFactory2 factory = (XMLInputFactory2) XMLInputFactory2.newInstance();
      factory.configureForLowMemUsage();
      XMLStreamReader xmlStreamReader = factory.createXMLStreamReader(buffer);
      xmlStreamReader.close();
      Charset encoding =
          ofNullable(xmlStreamReader.getEncoding())
              .map(Charset::forName)
              .orElse(Charset.defaultCharset());

      buffer.reset();
      return Pair.of(IOUtils.toString(buffer, encoding), encoding);
    }
  }

  /**
   * Reads an XML from a file.
   *
   * @param file The file to read.
   * @return Pair&lt;String, Charset&gt; The contents of the file with the charset of the file
   * @throws IOException thrown on problems with reading from the file
   * @throws XMLStreamException thrown if there are problems parsing the file
   */
  public static Pair<String, Charset> readXml(File file) throws IOException, XMLStreamException {
    try (InputStream is = Files.newInputStream(file.toPath())) {
      return readXml(is);
    }
  }

  /**
   * Returns the GAV coordinates of a model.
   *
   * @param model the model.
   * @return the GAV coordinates of a model.
   */
  public static String getGAV(Model model) {
    return getGroupId(model) + ":" + getArtifactId(model) + ":" + getVersion(model);
  }

  @SuppressWarnings("UnusedReturnValue")
  public static class PropertyVersionsBuilder {

    @Getter private final String name;

    @Nullable private final String profileId;

    @Getter private final Set<ArtifactAssociation> associations = new TreeSet<>();
    private final Map<String, Boolean> upperBounds = new LinkedHashMap<>();
    private final Map<String, Boolean> lowerBounds = new LinkedHashMap<>();
    private final Log log;
    private final ResolverAdapter resolverAdapter;
    @Nullable private ArtifactVersion currentVersion;
    @Nullable private VersionRange currentVersionRange;

    PropertyVersionsBuilder(
        ResolverAdapter resolverAdapter, @Nullable String profileId, String name, Log log) {
      this.profileId = profileId;
      this.name = name;
      this.log = log;
      this.resolverAdapter = resolverAdapter;
    }

    public PropertyVersionsBuilder withAssociation(
        Artifact artifact, boolean usePluginRepositories) {
      this.associations.add(new DefaultArtifactAssociation(artifact, usePluginRepositories));
      return this;
    }

    public void clearAssociations() {
      this.associations.clear();
    }

    public boolean isAssociated() {
      return !this.associations.isEmpty();
    }

    public PropertyVersions build() throws VersionRetrievalException {
      SortedSet<ArtifactVersion> resolvedVersions = resolveAssociatedVersions(associations);
      PropertyVersions instance =
          new PropertyVersions(profileId, name, log, associations, resolvedVersions);
      instance.setCurrentVersion(currentVersion);
      instance.setCurrentVersionRange(currentVersionRange);
      return instance;
    }

    public @Nullable String getVersionRange() {
      if (lowerBounds.isEmpty() && upperBounds.isEmpty()) {
        return null;
      } else {
        Optional<Pair<ArtifactVersion, Boolean>> lowerBound =
            lowerBounds.entrySet().stream()
                .min(Entry.comparingByKey())
                .map((e) -> Pair.of(e.getKey(), e.getValue()))
                .map(
                    (p) ->
                        Pair.of(
                            ArtifactVersionService.getArtifactVersion(p.getKey()), p.getValue()));
        Optional<Pair<ArtifactVersion, Boolean>> upperBound =
            upperBounds.entrySet().stream()
                .max(Entry.comparingByKey())
                .map((e) -> Pair.of(e.getKey(), e.getValue()))
                .map(
                    (p) ->
                        Pair.of(
                            ArtifactVersionService.getArtifactVersion(p.getKey()), p.getValue()));
        return (lowerBound.map(Pair::getValue).orElse(false) ? '[' : '(')
            + lowerBound.map(Pair::getKey).map(Object::toString).orElse("")
            + ','
            + upperBound.map(Pair::getKey).map(Object::toString).orElse("")
            + (upperBound.map(Pair::getValue).orElse(false) ? ']' : ')');
      }
    }

    public PropertyVersionsBuilder withLowerBound(String lowerBound, boolean includeLower) {
      this.lowerBounds.compute(
          lowerBound, (__, oldValue) -> Optional.ofNullable(oldValue).orElse(true) && includeLower);
      return this;
    }

    public PropertyVersionsBuilder withUpperBound(String upperBound, boolean includeUpper) {
      this.upperBounds.compute(
          upperBound, (__, oldValue) -> Optional.ofNullable(oldValue).orElse(true) && includeUpper);
      return this;
    }

    public PropertyVersionsBuilder withCurrentVersion(ArtifactVersion currentVersion) {
      this.currentVersion = currentVersion;
      return this;
    }

    public PropertyVersionsBuilder withCurrentVersionRange(VersionRange currentVersionRange) {
      this.currentVersionRange = currentVersionRange;
      return this;
    }

    private SortedSet<ArtifactVersion> resolveAssociatedVersions(
        ResolverAdapter resolverAdapter, Set<ArtifactAssociation> associations)
        throws VersionRetrievalException {
      SortedSet<ArtifactVersion> result = new TreeSet<>();

      for (ArtifactAssociation association : associations) {
        ArtifactVersions artifactVersions =
            resolverAdapter.resolveArtifactVersions(
                association.getArtifact(),
                association.isUsePluginRepositories(),
                !association.isUsePluginRepositories());
        List<ArtifactVersion> associatedVersions =
            Arrays.asList(artifactVersions.getVersions(true));
        if (result.isEmpty()) {
          result.addAll(associatedVersions);
        } else {
          result.retainAll(associatedVersions);
        }
      }

      return result;
    }

    /**
     * @deprecated
     */
    @SuppressWarnings("DeprecatedIsStillUsed")
    @Deprecated
    private SortedSet<ArtifactVersion> resolveAssociatedVersions(
        Set<ArtifactAssociation> associations) throws VersionRetrievalException {
      SortedSet<ArtifactVersion> result = new TreeSet<>();

      for (ArtifactAssociation association : associations) {
        ArtifactVersions artifactVersions =
            this.resolverAdapter.resolveArtifactVersions(
                association.getArtifact(),
                association.isUsePluginRepositories(),
                !association.isUsePluginRepositories());
        List<ArtifactVersion> associatedVersions =
            Arrays.asList(artifactVersions.getVersions(true));
        if (result.isEmpty()) {
          result.addAll(associatedVersions);
        } else {
          result.retainAll(associatedVersions);
        }
      }

      return result;
    }
  }

  public static class PropertyVersions extends AbstractVersionDetails {

    @Getter private final String name;

    @Getter @Nullable private final String profileId;
    private final Set<ArtifactAssociation> associations;
    private final SortedSet<ArtifactVersion> resolvedVersions;
    private final Log log;

    PropertyVersions(
        @Nullable String profileId,
        String name,
        Log log,
        Set<ArtifactAssociation> associations,
        SortedSet<ArtifactVersion> resolvedVersions) {
      this.profileId = profileId;
      this.name = name;
      this.log = log;
      this.associations = new TreeSet<>(associations);
      this.resolvedVersions = resolvedVersions;
    }

    public ArtifactAssociation[] getAssociations() {
      return this.associations.toArray(new ArtifactAssociation[0]);
    }

    public ArtifactVersion[] getVersions(Collection<Artifact> artifacts) {
      SortedSet<ArtifactVersion> result = new TreeSet<>();

      for (ArtifactAssociation association : this.associations) {
        for (Artifact artifact : artifacts) {
          if (association.getArtifact().getGroupId().equals(artifact.getGroupId())
              && association.getArtifact().getArtifactId().equals(artifact.getArtifactId())) {
            try {
              result.add(artifact.getSelectedVersion());
            } catch (OverConstrainedVersionException ignored) {
            }
          }
        }
      }

      result.removeIf(
          (candidate) ->
              this.associations.stream()
                  .anyMatch(
                      (association) ->
                          artifacts.stream()
                              .noneMatch(
                                  (artifact) -> {
                                    try {
                                      return association
                                              .getArtifact()
                                              .getGroupId()
                                              .equals(artifact.getGroupId())
                                          && association
                                              .getArtifact()
                                              .getArtifactId()
                                              .equals(artifact.getArtifactId())
                                          && candidate
                                              .toString()
                                              .equals(artifact.getSelectedVersion().toString());
                                    } catch (OverConstrainedVersionException var4) {
                                      return false;
                                    }
                                  })));
      return result.toArray(new ArtifactVersion[0]);
    }

    public ArtifactVersion[] getVersions(boolean includeSnapshots) {
      return this.resolvedVersions.stream()
          .filter((v) -> includeSnapshots || !ArtifactUtils.isSnapshot(v.toString()))
          .toArray(ArtifactVersion[]::new);
    }

    public boolean isAssociated() {
      return !this.associations.isEmpty();
    }

    public String toString() {
      return "PropertyVersions{"
          + (this.profileId == null ? "" : "profileId='" + this.profileId + "', ")
          + "name='"
          + this.name
          + '\''
          + ", associations="
          + this.associations
          + '}';
    }

    public ArtifactVersion getNewestVersion(
        String versionString,
        Property property,
        boolean allowSnapshots,
        Collection<MavenProject> reactorProjects,
        boolean allowDowngrade,
        Optional<Segment> upperBoundSegment)
        throws InvalidSegmentException, InvalidVersionSpecificationException {
      boolean includeSnapshots = !property.isBanSnapshots() && allowSnapshots;
      this.log.debug("getNewestVersion(): includeSnapshots='" + includeSnapshots + "'");
      this.log.debug(
          "Property ${"
              + property.getName()
              + "}: Set of valid available versions is "
              + Arrays.asList(this.getVersions(includeSnapshots)));
      VersionRange range =
          property.getVersion() != null
              ? VersionRange.createFromVersionSpec(property.getVersion())
              : null;
      this.log.debug("Property ${" + property.getName() + "}: Restricting results to " + range);
      ArtifactVersion currentVersion = ArtifactVersionService.getArtifactVersion(versionString);
      ArtifactVersion lowerBound =
          allowDowngrade
              ? this.getLowerBound(currentVersion, upperBoundSegment)
                  .map(ArtifactVersionService::getArtifactVersion)
                  .orElse(null)
              : currentVersion;
      this.log.debug("lowerBoundArtifactVersion: " + lowerBound);
      ArtifactVersion upperBound =
          upperBoundSegment.isPresent()
              ? upperBoundSegment
                  .map(
                      (s) ->
                          new BoundArtifactVersion(
                              currentVersion,
                              s.isMajorTo(Segment.SUBINCREMENTAL) ? Segment.minorTo(s) : s))
                  .orElse(null)
              : null;
      this.log.debug("Property ${" + property.getName() + "}: upperBound is: " + upperBound);
      Restriction restriction =
          new Restriction(lowerBound, allowDowngrade, upperBound, allowDowngrade);
      ArtifactVersion result = this.getNewestVersion(range, restriction, includeSnapshots);
      this.log.debug("Property ${" + property.getName() + "}: Current winner is: " + result);
      if (property.isSearchReactor()) {
        this.log.debug(
            "Property ${" + property.getName() + "}: Searching reactor for a valid version...");
        Set<Artifact> reactorArtifacts =
            reactorProjects.stream().map(MavenProject::getArtifact).collect(Collectors.toSet());
        ArtifactVersion[] reactorVersions = this.getVersions(reactorArtifacts);
        this.log.debug(
            "Property ${"
                + property.getName()
                + "}: Set of valid available versions from the reactor is "
                + Arrays.asList(reactorVersions));
        ArtifactVersion fromReactor =
            Arrays.stream(reactorVersions)
                .filter(
                    (version) -> range == null || ArtifactVersions.isVersionInRange(version, range))
                .reduce((first, second) -> second)
                .orElse(null);
        if (fromReactor != null && (result == null || !currentVersion.equals(fromReactor))) {
          result =
              !property.isPreferReactor() && result != null
                  ? (result.compareTo(fromReactor) < 0 ? fromReactor : result)
                  : fromReactor;
          this.log.debug(
              "Property ${"
                  + property.getName()
                  + "}: "
                  + (result.equals(fromReactor)
                      ? "Reactor has a valid version"
                      : "Reactor has the same or older version"));
        }
      }

      return result;
    }
  }

  @Getter
  static final class DefaultArtifactAssociation implements ArtifactAssociation {
    private final Artifact artifact;

    private final boolean usePluginRepositories;

    DefaultArtifactAssociation(Artifact artifact, boolean usePluginRepositories) {
      Objects.requireNonNull(artifact);
      this.artifact = artifact;
      this.usePluginRepositories = usePluginRepositories;
    }

    public int compareTo(@Nullable ArtifactAssociation o) {
      if (this == o) {
        return 0;
      }
      if (o == null || getClass() != o.getClass()) {
        return 1;
      }
      DefaultArtifactAssociation that = (DefaultArtifactAssociation) o;

      int rv = getArtifact().getGroupId().compareTo(that.getArtifact().getGroupId());
      if (rv != 0) {
        return rv;
      }
      rv = getArtifact().getArtifactId().compareTo(that.getArtifact().getArtifactId());
      if (rv != 0) {
        return rv;
      }
      if (usePluginRepositories != that.usePluginRepositories) {
        return usePluginRepositories ? 1 : -1;
      }
      return 0;
    }

    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      DefaultArtifactAssociation that = (DefaultArtifactAssociation) o;

      if (usePluginRepositories != that.usePluginRepositories) {
        return false;
      }
      if (!getArtifact().getArtifactId().equals(that.getArtifact().getArtifactId())) {
        return false;
      }
      return getArtifact().getGroupId().equals(that.getArtifact().getGroupId());
    }

    public int hashCode() {
      int result = getArtifact().getGroupId().hashCode();
      result = 31 * result + getArtifact().getArtifactId().hashCode();
      result = 31 * result + (usePluginRepositories ? 1 : 0);
      return result;
    }

    public String toString() {
      return (usePluginRepositories ? "plugin:" : "artifact:")
          + ArtifactUtils.versionlessKey(artifact);
    }
  }
}
