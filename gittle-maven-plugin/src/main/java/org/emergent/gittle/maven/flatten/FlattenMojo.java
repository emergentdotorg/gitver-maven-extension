package org.emergent.gittle.maven.flatten;

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

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Named;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.model.Repository;
import org.apache.maven.model.RepositoryPolicy;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.interpolation.ModelInterpolator;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RequestTrace;
import org.emergent.gittle.maven.flatten.cifriendly.CiInterpolator;
import org.emergent.gittle.maven.flatten.model.resolution.FlattenModelResolver;

@Mojo(name = "flatten", requiresDependencyCollection = ResolutionScope.RUNTIME, threadSafe = true)
public class FlattenMojo extends AbstractFlattenMojo {

  private static final int INITIAL_POM_WRITER_SIZE = 4096;

  private static final Pattern NEW_LINE_PATTERN = Pattern.compile("\\n|\\r\\n?");

  /** The {@link Settings} used to get active profile properties. */
  @Parameter(defaultValue = "${settings}", readonly = true, required = true)
  private Settings settings;

  /** The {@link MavenSession} used to get user properties. */
  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  private MavenSession session;

  /** The Maven Project. */
  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  /**
   * The flag to indicate if the generated flattened POM shall be set as POM file to the current
   * project. By default this is only done for projects with packaging other than <code>pom</code>.
   * You may want to also do this for <code>pom</code> packages projects by setting this parameter
   * to <code>true</code> or you can use <code>false</code> in order to only generate the flattened
   * POM but never set it as POM file. If <code>flattenMode</code> is set to bom the default value
   * will be <code>true</code>.
   */
  @Parameter(property = "updatePomFile", defaultValue = "true")
  private Boolean updatePomFile;

  /**
   * The {@link MojoExecution} used to get access to the raw configuration of pomElements as empty
   * tags are mapped to null.
   */
  @Parameter(defaultValue = "${mojo}", readonly = true, required = true)
  private MojoExecution mojoExecution;

  /** If {@code true} the flatten goal will be skipped. */
  @Parameter(property = "flatten.flatten.skip", defaultValue = "false")
  private boolean skipFlatten;

  /** The {@link ModelInterpolator} used to resolve variables. */
  @Inject
  @Named("model-interpolator")
  private CiInterpolator ciInterpolator;

  @Inject private RepositorySystem repositorySystem;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (shouldSkip()) {
      getLog().info("Flatten skipped.");
      return;
    }

    getLog().info("Generating flattened POM of project " + this.project.getId() + "...");

    File originalPomFile = this.project.getFile();
    Path flattenedPomFile = getFlattenedPomFile();

    /*
     * Non-destructive CI-friendly version flattening?
     *
     * NOTE: Regular flattening implies POM rebuilding from scratch, losing original formatting, ordering and
     * comments (`KeepCommentsInPom` itself is testament to this anything-but-ideal arrangement). Such side
     * effects are undesirable when it comes to POMs requiring just CI-friendly version interpolation, which are
     * typically expected to retain their original representation for later inspection. Despite far from elegant,
     * this dedicated solution ensures POMs are flattened non-destructively.
     */
    ModelsFactory modelsFactory = new ModelsFactory(originalPomFile);
    String modelEncoding = getModelEncoding(modelsFactory.getEffectivePom());
    // String revisionVariablePattern = String.format("${%s}", "revision");

    // // Load original POM content!
    // String pomString;
    // try {
    //   pomString = new String(Files.readAllBytes(originalPomFile.toPath()), modelEncoding);
    // } catch (IOException e) {
    //   throw new MojoExecutionException("Original POM file loading FAILED: " + originalPomFile,
    // e);
    // }

    // Interpolate POM content!
    // CiInterpolator ciInterpolator = modelCiFriendlyInterpolator;
    // ciInterpolator.setRevisionVariablePattern(revisionVariablePattern);

    Model originalPom = project.getModel();

    // Model resolvedPom = modelsFactory.getResolvedPom();
    // Model resolvedPom = project.getModel();

    File projectDir = originalPom.getProjectDirectory();
    ModelBuildingRequest config = createModelBuildingRequest(originalPomFile);
    LoggingModelProblemCollector problems = new LoggingModelProblemCollector(getLog());

    // Model originalModel = createOriginalPom(originalPomFile.toPath());
    // pomString = ciInterpolator.interpolateModelContent(pomString, originalPom, projectDir,
    // config, problems);
    Model flattenedPom = ciInterpolator.interpolateModel(originalPom, projectDir, config, problems);

    // Save flattened POM content!
    // writeStringToFile(pomString, flattenedPomFile, modelEncoding);
    writePom(flattenedPomFile, modelEncoding, flattenedPom);

    // // Load flattened POM!
    // flattenedPom = createOriginalPom(flattenedPomFile);

    if (isUpdatePomFile()) {
      project.setPomFile(flattenedPomFile.toFile());
      project.setOriginalModel(flattenedPom);
    }
  }

  @Override
  protected boolean shouldSkipGoal() {
    return skipFlatten;
  }

  /**
   * Writes the given <code>data</code> to the given <code>file</code> using the specified <code>
   * encoding</code>.
   *
   * @param data is the {@link String} to write.
   * @param file is the {@link Path} to write to.
   * @param encoding is the encoding to use for writing the file.
   * @throws MojoExecutionException if anything goes wrong.
   */
  protected void writeStringToFile(String data, Path file, String encoding)
      throws MojoExecutionException {
    data = NEW_LINE_PATTERN.matcher(data).replaceAll(System.lineSeparator());

    try {
      byte[] binaryData = data.getBytes(encoding);
      if (Files.isReadable(file) && Files.size(file) == binaryData.length) {
        try {
          byte[] buffer = Files.readAllBytes(file);
          if (Arrays.equals(buffer, binaryData)) {
            getLog().debug("Arrays.equals( buffer, binaryData ) ");
            return;
          }
          getLog().debug("Not Arrays.equals( buffer, binaryData ) ");
        } catch (IOException e) {
          // ignore those exceptions, we will overwrite the file
          getLog().debug("Issue reading file: " + file, e);
        }
      } else {
        getLog()
            .debug(
                "file: "
                    + file
                    + ", not exist or has the same length as binaryData.length: "
                    + binaryData.length);
      }
      Path parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.write(file, binaryData, CREATE, TRUNCATE_EXISTING, WRITE);
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to write to " + file, e);
    }
  }

  Model createEffectivePom(ModelBuildingRequest buildingRequest) throws MojoExecutionException {
    try {
      return createEffectivePomImpl(buildingRequest);
    } catch (Exception e) {
      throw new MojoExecutionException("failed to create the effective pom", e);
    }
  }

  private Model createOriginalPom(Path pomFile) throws MojoExecutionException {
    MavenXpp3Reader reader = new MavenXpp3Reader();
    try {
      return reader.read(Files.newInputStream(pomFile));
    } catch (IOException | XmlPullParserException e) {
      throw new MojoExecutionException("Error reading raw model.", e);
    }
  }

  private void writePom(Path pomFile, String encoding, Model model) throws MojoExecutionException {
    Charset cs = Charset.forName(encoding);
    try (BufferedWriter writer =
        Files.newBufferedWriter(pomFile, cs, CREATE, TRUNCATE_EXISTING, WRITE)) {
      new MavenXpp3Writer().write(writer, model);
    } catch (IOException e) {
      throw new MojoExecutionException("Error reading raw model.", e);
    }
  }

  /**
   * Creates a flattened {@link List} of {@link Repository} elements where those from super-POM are
   * omitted.
   *
   * @param repositories is the {@link List} of {@link Repository} elements. May be <code>null
   *     </code>.
   * @return the flattened {@link List} of {@link Repository} elements or <code>null</code> if
   *     <code>null</code> was given.
   */
  protected static List<Repository> createFlattenedRepositories(List<Repository> repositories) {
    if (repositories != null) {
      List<Repository> flattenedRepositories = new ArrayList<>(repositories.size());
      for (Repository repo : repositories) {
        // filter inherited repository section from super POM (see MOJO-2042)...
        if (!isCentralRepositoryFromSuperPom(repo)) {
          flattenedRepositories.add(repo);
        }
      }
      return flattenedRepositories;
    }
    return null;
  }

  private static String getModelEncoding(Model pom) {
    // keep original encoding (we could also normalize to UTF-8 here)
    String modelEncoding = pom.getModelEncoding();
    if (StringUtils.isEmpty(modelEncoding)) {
      modelEncoding = "UTF-8";
    }
    return modelEncoding;
  }

  /**
   * This method determines if the given {@link Repository} section is identical to what is defined
   * from the super POM.
   *
   * @param repo is the {@link Repository} section to check.
   * @return <code>true</code> if maven central default configuration, <code>false</code> otherwise.
   */
  private static boolean isCentralRepositoryFromSuperPom(Repository repo) {
    if (repo != null) {
      if ("central".equals(repo.getId())) {
        RepositoryPolicy snapshots = repo.getSnapshots();
        return snapshots != null && !snapshots.isEnabled();
      }
    }
    return false;
  }

  private ModelBuildingRequest createModelBuildingRequest(File pomFile) {
    RequestTrace trace = new RequestTrace(pomFile);
    String context = mojoExecution.getExecutionId();
    FlattenModelResolver resolver =
        new FlattenModelResolver(
            session.getRepositorySession(),
            repositorySystem,
            trace,
            context,
            project.getRemoteProjectRepositories(),
            getReactorModelsFromSession());
    Properties userAndActiveExternalProfilesProperties = new Properties();
    userAndActiveExternalProfilesProperties.putAll(this.session.getUserProperties());
    this.settings.getProfiles().stream()
        .filter(
            p ->
                this.settings.getActiveProfiles().contains(p.getId())
                    && !this.session.getRequest().getInactiveProfiles().contains(p.getId()))
        .forEach(
            activeProfile ->
                userAndActiveExternalProfilesProperties.putAll(activeProfile.getProperties()));

    List<String> activeProfiles =
        Stream.concat(
                this.session.getRequest().getActiveProfiles().stream(),
                this.settings.getActiveProfiles().stream())
            .collect(Collectors.toList());

    return new DefaultModelBuildingRequest()
        .setUserProperties(userAndActiveExternalProfilesProperties)
        .setSystemProperties(System.getProperties())
        .setPomFile(pomFile)
        .setModelResolver(resolver)
        .setActiveProfileIds(activeProfiles)
        .setInactiveProfileIds(this.session.getRequest().getInactiveProfiles());
  }

  private List<MavenProject> getReactorModelsFromSession() {
    // robust approach for 'special' environments like m2e (Eclipse plugin) which don't provide
    // allProjects
    List<MavenProject> models = this.session.getAllProjects();
    if (models == null) {
      models = this.session.getProjects();
    }
    if (models == null) {
      models = Collections.emptyList();
    }
    return models;
  }

  /**
   * Creates the effective POM for the given <code>pomFile</code> trying its best to match the core
   * maven behaviour.
   *
   * @param buildingRequest {@link ModelBuildingRequest}
   * @return the parsed and calculated effective POM.
   * @throws MojoExecutionException if anything goes wrong.
   */
  protected Model createEffectivePomImpl(ModelBuildingRequest buildingRequest)
      throws MojoExecutionException {
    ModelBuildingResult buildingResult;
    try {
      buildingResult = new DefaultModelBuilderFactory().newInstance().build(buildingRequest);
    } catch (ModelBuildingException e) {
      throw new MojoExecutionException(e.getMessage(), e);
    }

    Model effectivePom = buildingResult.getEffectiveModel();

    // LoggingModelProblemCollector problems = new LoggingModelProblemCollector( getLog() );
    // Model interpolatedModel =
    // this.modelInterpolator.interpolateModel( this.project.getOriginalModel(),
    // effectivePom.getProjectDirectory(), buildingRequest, problems );

    // remove Repositories from super POM (central)
    // effectivePom.setRepositories(createFlattenedRepositories(effectivePom.getRepositories()));
    return effectivePom;
  }

  /**
   * @return <code>true</code> if the generated flattened POM shall be {@link
   *     MavenProject#setFile(java.io.File) set} as POM artifact of the {@link MavenProject}, <code>
   *     false</code> otherwise.
   */
  public boolean isUpdatePomFile() {
    if (this.updatePomFile == null) {
      if (this.project.getPackaging().equals("pom")) {
        getLog()
            .info(
                "Project POM file not updated to point to the flattened POM due to 'pom'"
                    + " packaging");
        return false;
      } else {
        return true;
      }
    } else {
      return this.updatePomFile;
    }
  }

  private class ModelsFactory {
    private final File pomFile;
    private Model effectivePom;
    private Model originalPom;
    private Model resolvedPom;

    private ModelsFactory(File pomFile) {
      this.pomFile = pomFile;
    }

    public Model getEffectivePom() throws MojoExecutionException {
      if (effectivePom == null) {
        this.effectivePom =
            FlattenMojo.this.createEffectivePom(createModelBuildingRequest(pomFile)).clone();
      }
      return this.effectivePom;
    }

    public Model getOriginalPom() throws MojoExecutionException {
      if (this.originalPom == null) {
        this.originalPom = createOriginalPom(this.pomFile.toPath());
      }
      return this.originalPom;
    }

    public Model getResolvedPom() {
      if (this.resolvedPom == null) {
        this.resolvedPom = FlattenMojo.this.project.getModel();
      }
      return this.resolvedPom;
    }
  }
}
