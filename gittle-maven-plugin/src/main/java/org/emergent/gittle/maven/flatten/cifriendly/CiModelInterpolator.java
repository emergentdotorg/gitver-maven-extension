package org.emergent.gittle.maven.flatten.cifriendly;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.Singular;
import lombok.Value;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelProblem.Severity;
import org.apache.maven.model.building.ModelProblem.Version;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.building.ModelProblemCollectorRequest;
import org.apache.maven.model.interpolation.MavenBuildTimestamp;
import org.apache.maven.model.path.PathTranslator;
import org.apache.maven.model.path.UrlNormalizer;
import org.codehaus.plexus.interpolation.AbstractValueSource;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.codehaus.plexus.interpolation.InterpolationPostProcessor;
import org.codehaus.plexus.interpolation.Interpolator;
import org.codehaus.plexus.interpolation.MapBasedValueSource;
import org.codehaus.plexus.interpolation.ObjectBasedValueSource;
import org.codehaus.plexus.interpolation.PrefixAwareRecursionInterceptor;
import org.codehaus.plexus.interpolation.PrefixedObjectValueSource;
import org.codehaus.plexus.interpolation.PrefixedValueSourceWrapper;
import org.codehaus.plexus.interpolation.RecursionInterceptor;
import org.codehaus.plexus.interpolation.ValueSource;
import org.codehaus.plexus.interpolation.util.ValueSourceUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Based on StringSearchModelInterpolator in maven-model-builder. */
@Singleton
@Named("model-interpolator")
public class CiModelInterpolator implements CiInterpolator {

  private static final List<String> PROJECT_PREFIXES = Arrays.asList("pom.", "project.");

  private static final Collection<String> TRANSLATED_PATH_EXPRESSIONS;

  private static final Map<Class<?>, InterpolateObjectAction.CacheItem> CACHED_ENTRIES =
      new ConcurrentHashMap<>(80, 0.75f, 2);

  // Empirical data from 3.x, actual =40

  static {
    Collection<String> translatedPrefixes = new HashSet<>();

    // MNG-1927, MNG-2124, MNG-3355:
    // If the build section is present and the project directory is
    // non-null, we should make
    // sure interpolation of the directories below uses translated paths.
    // Afterward, we'll double back and translate any paths that weren't
    // covered during interpolation via the
    // code below...
    translatedPrefixes.add("build.directory");
    translatedPrefixes.add("build.outputDirectory");
    translatedPrefixes.add("build.testOutputDirectory");
    translatedPrefixes.add("build.sourceDirectory");
    translatedPrefixes.add("build.testSourceDirectory");
    translatedPrefixes.add("build.scriptSourceDirectory");
    translatedPrefixes.add("reporting.outputDirectory");

    TRANSLATED_PATH_EXPRESSIONS = translatedPrefixes;
  }

  @Getter(value = AccessLevel.PROTECTED)
  @Setter(value = AccessLevel.PROTECTED)
  private RecursionInterceptor recursionInterceptor;

  private final PathTranslator pathTranslator;

  private final UrlNormalizer urlNormalizer;

  // @Setter private String revisionVariablePattern;

  @Inject
  public CiModelInterpolator(PathTranslator pathTranslator, UrlNormalizer urlNormalizer) {
    this.pathTranslator = pathTranslator;
    this.urlNormalizer = urlNormalizer;
    this.recursionInterceptor = new PrefixAwareRecursionInterceptor(PROJECT_PREFIXES);
  }

  @Override
  public Model interpolateModel(
      Model model, File projectDir, ModelBuildingRequest config, ModelProblemCollector problems) {
    Model mutating = model.clone();
    interpolateObject(mutating, model, projectDir, config, problems);
    return model;
  }

  public String interpolateModelContent(
      String modelContent,
      Model model,
      File projectDir,
      ModelBuildingRequest config,
      ModelProblemCollector problems) {
    try (InternalInterpolator interp =
        createInternalInterpolator(model, projectDir, config, problems)) {
      return interp.interpolateInternal(modelContent);
    }
  }

  protected void interpolateObject(
      Object obj,
      Model model,
      File projectDir,
      ModelBuildingRequest config,
      ModelProblemCollector problems) {
    try (InterpolateObjectAction interp =
        createInterpolateObjectAction(model, projectDir, config, problems)) {
      interp.addTarget(obj).run();
    }
  }

  private @NonNull InterpolateObjectAction createInterpolateObjectAction(
      Model model, File projectDir, ModelBuildingRequest config, ModelProblemCollector problems) {
    return new InterpolateObjectAction(
        createInternalInterpolator(model, projectDir, config, problems), problems);
  }

  private @NonNull InternalInterpolator createInternalInterpolator(
      Model model, File projectDir, ModelBuildingRequest config, ModelProblemCollector problems) {
    List<? extends ValueSource> valueSources =
        createValueSources(model, projectDir, config, problems);
    List<? extends InterpolationPostProcessor> postProcessors =
        createPostProcessors(model, projectDir, config);
    Interpolator interpolator = new CiInterpolatorImpl();
    interpolator.setCacheAnswers(true);
    valueSources.forEach(interpolator::addValueSource);
    postProcessors.forEach(interpolator::addPostProcessor);
    return new InternalInterpolator(interpolator, problems);
  }

  @Value
  public class InternalInterpolator implements AutoCloseable {

    Interpolator interpolator;
    ModelProblemCollector problems;

    @Nullable String interpolateInternal(String src) {
      if (src == null || DEFAULT_RESOLVABLE_EXPR.stream().noneMatch(src::contains)) {
        return src;
      }
      String result = src;
      try {
        result = interpolator.interpolate(result, getRecursionInterceptor());
      } catch (InterpolationException e) {
        problems.add(
            new ModelProblemCollectorRequest(Severity.ERROR, Version.BASE)
                .setMessage(e.getMessage())
                .setException(e));
      }
      interpolator.clearFeedback();
      return result;
    }

    @Override
    public void close() {
      interpolator.clearAnswers();
    }
  }

  private record InterpolateObjectAction(
      InternalInterpolator modelInterpolator,
      ModelProblemCollector problems,
      LinkedList<Object> interpolationTargets)
      implements Runnable, AutoCloseable {

    InterpolateObjectAction(
        InternalInterpolator modelInterpolator, ModelProblemCollector problems) {
      this(modelInterpolator, problems, new LinkedList<>());
    }

    @Override
    public void run() {
      while (!interpolationTargets.isEmpty()) {
        Object obj = interpolationTargets.removeFirst();
        traverseObjectWithParents(obj.getClass(), obj);
      }
    }

    public InterpolateObjectAction addTarget(Object target) {
      interpolationTargets.add(target);
      return this;
    }

    public InterpolateObjectAction addTargets(Collection<Object> targets) {
      interpolationTargets.add(targets);
      return this;
    }

    @Override
    public void close() {
      modelInterpolator.close();
    }

    private String interpolate(String value) {
      return modelInterpolator.interpolateInternal(value);
    }

    private void traverseObjectWithParents(Class<?> cls, Object target) {
      if (cls == null) {
        return;
      }

      CacheItem cacheEntry = getCacheEntry(cls);
      if (cacheEntry.isArray()) {
        evaluateArray(target, this);
      } else if (cacheEntry.isQualifiedForInterpolation) {
        cacheEntry.interpolate(target, this);

        traverseObjectWithParents(cls.getSuperclass(), target);
      }
    }

    private CacheItem getCacheEntry(Class<?> cls) {
      CacheItem cacheItem = CACHED_ENTRIES.get(cls);
      if (cacheItem == null) {
        cacheItem = new CacheItem(cls);
        CACHED_ENTRIES.put(cls, cacheItem);
      }
      return cacheItem;
    }

    private static void evaluateArray(Object target, InterpolateObjectAction ctx) {
      int len = Array.getLength(target);
      for (int i = 0; i < len; i++) {
        Object value = Array.get(target, i);
        if (value != null) {
          if (String.class == value.getClass()) {
            String interpolated = ctx.interpolate((String) value);

            if (!interpolated.equals(value)) {
              Array.set(target, i, interpolated);
            }
          } else {
            ctx.interpolationTargets.add(value);
          }
        }
      }
    }

    private static class CacheItem {
      @Getter private final boolean isArray;

      private final boolean isQualifiedForInterpolation;

      private final CacheField[] fields;

      private boolean isQualifiedForInterpolation(Class<?> cls) {
        return !cls.getName().startsWith("java");
      }

      private boolean isQualifiedForInterpolation(Field field, Class<?> fieldType) {
        if (Map.class.equals(fieldType) && "locations".equals(field.getName())) {
          return false;
        }

        // noinspection SimplifiableIfStatement
        if (fieldType.isPrimitive()) {
          return false;
        }

        return !"parent".equals(field.getName());
      }

      CacheItem(Class<?> clazz) {
        this.isQualifiedForInterpolation = isQualifiedForInterpolation(clazz);
        this.isArray = clazz.isArray();
        List<CacheField> fields = new ArrayList<>();
        for (Field currentField : clazz.getDeclaredFields()) {
          Class<?> type = currentField.getType();
          if (isQualifiedForInterpolation(currentField, type)) {
            if (String.class == type) {
              if (!Modifier.isFinal(currentField.getModifiers())) {
                fields.add(new StringField(currentField));
              }
            } else if (List.class.isAssignableFrom(type)) {
              fields.add(new ListField(currentField));
            } else if (Collection.class.isAssignableFrom(type)) {
              throw new RuntimeException(
                  "We dont interpolate into collections, use a list instead");
            } else if (Map.class.isAssignableFrom(type)) {
              fields.add(new MapField(currentField));
            } else {
              fields.add(new ObjectField(currentField));
            }
          }
        }
        this.fields = fields.toArray(new CacheField[0]);
      }

      public void interpolate(Object target, InterpolateObjectAction interpolateObjectAction) {
        for (CacheField field : fields) {
          field.interpolate(target, interpolateObjectAction);
        }
      }
    }

    abstract static class CacheField {
      protected final Field field;

      CacheField(Field field) {
        this.field = field;
      }

      void interpolate(Object target, InterpolateObjectAction action) {
        synchronized (field) {
          boolean isAccessible = field.isAccessible();
          field.setAccessible(true);
          try {
            doInterpolate(target, action);
          } catch (IllegalArgumentException e) {
            action.problems.add(
                new ModelProblemCollectorRequest(Severity.ERROR, Version.BASE)
                    .setMessage(
                        "Failed to interpolate field3: "
                            + field
                            + " on class: "
                            + field.getType().getName())
                    .setException(e)); // todo: Not entirely
            // the same message
          } catch (IllegalAccessException e) {
            action.problems.add(
                new ModelProblemCollectorRequest(Severity.ERROR, Version.BASE)
                    .setMessage(
                        "Failed to interpolate field4: "
                            + field
                            + " on class: "
                            + field.getType().getName())
                    .setException(e));
          } finally {
            field.setAccessible(isAccessible);
          }
        }
      }

      abstract void doInterpolate(Object target, InterpolateObjectAction ctx)
          throws IllegalAccessException;

      @Override
      public String toString() {
        return field.toString();
      }
    }

    static final class StringField extends CacheField {
      StringField(Field field) {
        super(field);
      }

      @Override
      void doInterpolate(Object target, InterpolateObjectAction ctx) throws IllegalAccessException {
        String value = (String) field.get(target);
        if (value == null) {
          return;
        }
        String interpolated = ctx.interpolate(value);
        if (!interpolated.equals(value)) {
          field.set(target, interpolated);
        }
      }
    }

    static final class ListField extends CacheField {
      ListField(Field field) {
        super(field);
      }

      @Override
      void doInterpolate(Object target, InterpolateObjectAction ctx) throws IllegalAccessException {
        @SuppressWarnings("unchecked")
        List<Object> c = (List<Object>) field.get(target);
        if (c == null) {
          return;
        }

        int size = c.size();
        Object value;
        for (int i = 0; i < size; i++) {

          value = c.get(i);

          if (value != null) {
            if (String.class == value.getClass()) {
              String interpolated = ctx.interpolate((String) value);

              if (!interpolated.equals(value)) {
                try {
                  c.set(i, interpolated);
                } catch (UnsupportedOperationException e) {
                  return;
                }
              }
            } else {
              if (value.getClass().isArray()) {
                evaluateArray(value, ctx);
              } else {
                ctx.interpolationTargets.add(value);
              }
            }
          }
        }
      }
    }

    static final class MapField extends CacheField {
      MapField(Field field) {
        super(field);
      }

      @Override
      void doInterpolate(Object target, InterpolateObjectAction ctx) throws IllegalAccessException {
        @SuppressWarnings("unchecked")
        Map<Object, Object> m = (Map<Object, Object>) field.get(target);
        if (m == null || m.isEmpty()) {
          return;
        }

        for (Entry<Object, Object> entry : m.entrySet()) {
          Object value = entry.getValue();

          if (value == null) {
            continue;
          }

          if (String.class == value.getClass()) {
            String interpolated = ctx.interpolate((String) value);

            if (!interpolated.equals(value)) {
              try {
                entry.setValue(interpolated);
              } catch (UnsupportedOperationException ignore) {
                // nop
              }
            }
          } else if (value.getClass().isArray()) {
            evaluateArray(value, ctx);
          } else {
            ctx.interpolationTargets.add(value);
          }
        }
      }
    }

    static final class ObjectField extends CacheField {
      private final boolean isArray;

      ObjectField(Field field) {
        super(field);
        this.isArray = field.getType().isArray();
      }

      @Override
      void doInterpolate(Object target, InterpolateObjectAction ctx) throws IllegalAccessException {
        Object value = field.get(target);
        if (value != null) {
          if (isArray) {
            evaluateArray(value, ctx);
          } else {
            ctx.interpolationTargets.add(value);
          }
        }
      }
    }
  }

  protected List<ValueSource> createValueSources(
      final Model model,
      final File projectDir,
      final ModelBuildingRequest config,
      final ModelProblemCollector problems) {
    Properties modelProperties = model.getProperties();

    ValueSource modelValueSource1 = new PrefixedObjectValueSource(PROJECT_PREFIXES, model, false);
    if (config.getValidationLevel() >= ModelBuildingRequest.VALIDATION_LEVEL_MAVEN_2_0) {
      modelValueSource1 =
          new ProblemDetectingValueSource(modelValueSource1, "pom.", "project.", problems);
    }

    ValueSource modelValueSource2 = new ObjectBasedValueSource(model);
    if (config.getValidationLevel() >= ModelBuildingRequest.VALIDATION_LEVEL_MAVEN_2_0) {
      modelValueSource2 =
          new ProblemDetectingValueSource(modelValueSource2, "", "project.", problems);
    }

    // NOTE: Order counts here!
    List<ValueSource> valueSources = new ArrayList<>(9);

    if (projectDir != null) {
      ValueSource basedirValueSource =
          new PrefixedValueSourceWrapper(
              new AbstractValueSource(false) {
                public Object getValue(String expression) {
                  if ("basedir".equals(expression)) {
                    return projectDir.getAbsolutePath();
                  }
                  return null;
                }
              },
              PROJECT_PREFIXES,
              true);
      valueSources.add(basedirValueSource);

      ValueSource baseUriValueSource =
          new PrefixedValueSourceWrapper(
              new AbstractValueSource(false) {
                public Object getValue(String expression) {
                  if ("baseUri".equals(expression)) {
                    return projectDir.getAbsoluteFile().toURI().toString();
                  }
                  return null;
                }
              },
              PROJECT_PREFIXES,
              false);
      valueSources.add(baseUriValueSource);
      valueSources.add(new BuildTimestampValueSource(config.getBuildStartTime(), modelProperties));
    }

    valueSources.add(modelValueSource1);

    valueSources.add(new MapBasedValueSource(config.getUserProperties()));

    valueSources.add(new MapBasedValueSource(modelProperties));

    valueSources.add(new MapBasedValueSource(config.getSystemProperties()));

    valueSources.add(
        new AbstractValueSource(false) {
          public Object getValue(String expression) {
            return config.getSystemProperties().getProperty("env." + expression);
          }
        });

    valueSources.add(modelValueSource2);

    List<ValueSource> list = wrapValueSources(valueSources);
    return list;
  }

  private @NonNull List<ValueSource> wrapValueSources(List<ValueSource> valueSources) {
    return valueSources.stream().<ValueSource>map(LimitingValueSource::wrap).toList();
  }

  protected List<? extends InterpolationPostProcessor> createPostProcessors(
      Model model, @Nullable File projectDir, ModelBuildingRequest config) {
    List<InterpolationPostProcessor> processors = new ArrayList<>(2);
    if (projectDir != null) {
      processors.add(
          new PathTranslatingPostProcessor(
              PROJECT_PREFIXES, TRANSLATED_PATH_EXPRESSIONS, projectDir, pathTranslator));
    }
    processors.add(new UrlNormalizingPostProcessor(urlNormalizer));
    return processors;
  }

  static class BuildTimestampValueSource extends AbstractValueSource {
    private final MavenBuildTimestamp mavenBuildTimestamp;

    BuildTimestampValueSource(Date startTime, Properties properties) {
      super(false);
      this.mavenBuildTimestamp = new MavenBuildTimestamp(startTime, properties);
    }

    public Object getValue(String expression) {
      if ("build.timestamp".equals(expression) || "maven.build.timestamp".equals(expression)) {
        return mavenBuildTimestamp.formattedTimestamp();
      }
      return null;
    }
  }

  static class ProblemDetectingValueSource implements ValueSource {

    private final ValueSource valueSource;

    private final String bannedPrefix;

    private final String newPrefix;

    private final ModelProblemCollector problems;

    ProblemDetectingValueSource(
        ValueSource valueSource,
        String bannedPrefix,
        String newPrefix,
        ModelProblemCollector problems) {
      this.valueSource = valueSource;
      this.bannedPrefix = bannedPrefix;
      this.newPrefix = newPrefix;
      this.problems = problems;
    }

    public Object getValue(String expression) {
      Object value = valueSource.getValue(expression);

      if (value != null && expression.startsWith(bannedPrefix)) {
        String msg = "The expression ${" + expression + "} is deprecated.";
        if (newPrefix != null && !newPrefix.isEmpty()) {
          msg +=
              " Please use ${"
                  + newPrefix
                  + expression.substring(bannedPrefix.length())
                  + "} instead.";
        }
        problems.add(
            new ModelProblemCollectorRequest(Severity.WARNING, Version.V20).setMessage(msg));
      }

      return value;
    }

    public List<?> getFeedback() {
      return valueSource.getFeedback();
    }

    public void clearFeedback() {
      valueSource.clearFeedback();
    }
  }

  static class PathTranslatingPostProcessor implements InterpolationPostProcessor {

    private final Collection<String> unprefixedPathKeys;
    private final File projectDir;
    private final PathTranslator pathTranslator;
    private final List<String> expressionPrefixes;

    PathTranslatingPostProcessor(
        List<String> expressionPrefixes,
        Collection<String> unprefixedPathKeys,
        File projectDir,
        PathTranslator pathTranslator) {
      this.expressionPrefixes = expressionPrefixes;
      this.unprefixedPathKeys = unprefixedPathKeys;
      this.projectDir = projectDir;
      this.pathTranslator = pathTranslator;
    }

    public Object execute(String expression, Object value) {
      if (value != null) {
        expression = ValueSourceUtils.trimPrefix(expression, expressionPrefixes, true);

        if (unprefixedPathKeys.contains(expression)) {
          return pathTranslator.alignToBaseDirectory(String.valueOf(value), projectDir);
        }
      }

      return null;
    }
  }

  static class UrlNormalizingPostProcessor implements InterpolationPostProcessor {

    private final UrlNormalizer normalizer;

    UrlNormalizingPostProcessor(UrlNormalizer normalizer) {
      this.normalizer = normalizer;
    }

    public Object execute(String expression, @Nullable Object value) {
      Set<String> expressions = new HashSet<>();
      expressions.add("project.url");
      expressions.add("project.scm.url");
      expressions.add("project.scm.connection");
      expressions.add("project.scm.developerConnection");
      expressions.add("project.distributionManagement.site.url");

      if (value != null && expressions.contains(expression)) {
        return normalizer.normalize(value.toString());
      }

      return null;
    }
  }

  @Value
  @AllArgsConstructor
  @Builder(toBuilder = true, setterPrefix = "set", builderClassName = "Builder")
  private static class LimitingValueSource implements ValueSource {

    ValueSource wrapped;
    @Singular Set<String> resolvables;

    public static LimitingValueSource wrap(ValueSource wrapped) {
      return LimitingValueSource.builder()
          .setResolvables(DEFAULT_RESOLVABLES)
          .setWrapped(wrapped)
          .build();
    }

    public LimitingValueSource(ValueSource wrapped) {
      this(wrapped, DEFAULT_RESOLVABLES);
    }

    @Override
    public Object getValue(String expression) {
      return !resolvables.contains(expression) ? null : wrapped.getValue(expression);
    }

    @SuppressWarnings("rawtypes")
    @Override
    public List getFeedback() {
      return wrapped.getFeedback();
    }

    @Override
    public void clearFeedback() {
      wrapped.clearFeedback();
    }
  }
}
