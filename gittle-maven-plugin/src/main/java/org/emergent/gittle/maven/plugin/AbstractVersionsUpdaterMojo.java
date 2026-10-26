package org.emergent.gittle.maven.plugin;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Optional.ofNullable;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.inject.Inject;
import javax.xml.stream.XMLStreamException;
import lombok.Getter;
import lombok.Setter;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.wagon.Wagon;
import org.codehaus.mojo.versions.api.ArtifactVersions;
import org.codehaus.mojo.versions.api.DefaultVersionsHelper;
import org.codehaus.mojo.versions.api.PomHelper;
import org.codehaus.mojo.versions.api.Property;
import org.codehaus.mojo.versions.api.PropertyVersions;
import org.codehaus.mojo.versions.api.Segment;
import org.codehaus.mojo.versions.api.VersionRetrievalException;
import org.codehaus.mojo.versions.api.VersionsHelper;
import org.codehaus.mojo.versions.api.recording.ChangeRecorder;
import org.codehaus.mojo.versions.model.RuleSet;
import org.codehaus.mojo.versions.ordering.InvalidSegmentException;
import org.codehaus.mojo.versions.rewriting.MutableXMLStreamReader;
import org.codehaus.mojo.versions.rule.RuleService;
import org.codehaus.mojo.versions.rule.RulesServiceBuilder;
import org.codehaus.mojo.versions.utils.ArtifactFactory;
import org.codehaus.mojo.versions.utils.VersionsExpressionEvaluator;
import org.eclipse.aether.RepositorySystem;

public abstract class AbstractVersionsUpdaterMojo extends AbstractMojo {

  @Getter
  @Setter
  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  protected MavenProject project;

  /** The (injected) {@link org.eclipse.aether.RepositorySystem} instance. */
  protected RepositorySystem repositorySystem;

  /** The reactor projects for the current build. */
  @Parameter(defaultValue = "${reactorProjects}", required = true, readonly = true)
  protected List<MavenProject> reactorProjects;

  /**
   * settings.xml's server id for the URL. This is used when wagon needs extra authentication
   * information.
   */
  @Parameter(property = "maven.version.rules.serverId", defaultValue = "serverId")
  protected String serverId;

  /**
   * URI of a ruleSet file containing the rules that control how to compare version numbers. The URI
   * could be either a Wagon URI or a classpath URI (e.g. <code>
   * classpath:///package/sub/package/rules.xml</code>).
   */
  @Parameter(property = "maven.version.rules")
  protected String rulesUri;

  /** Controls whether a backup pom should be created. */
  @Parameter(property = "generateBackupPoms", defaultValue = "true")
  protected boolean generateBackupPoms;

  /** Our versions helper. */
  private VersionsHelper helper;

  /** The Maven Session. */
  @Parameter(defaultValue = "${session}", required = true, readonly = true)
  protected MavenSession session;

  /** The current mojo execution. */
  @Parameter(defaultValue = "${mojoExecution}", required = true, readonly = true)
  protected MojoExecution mojoExecution;

  /** The format used to record changes. If "none" is specified, no changes are recorded. */
  @Parameter(property = "changeRecorderFormat", defaultValue = "none")
  private String changeRecorderFormat = "none";

  /** The output file used to record changes. */
  @Parameter(
      property = "changeRecorderOutputFile",
      defaultValue = "${project.build.directory}/versions-changes.xml")
  private File changeRecorderOutputFile;

  /** The change recorders implementation. */
  private final Map<String, ChangeRecorder> changeRecorders;

  /**
   * Allows specifying the {@linkplain RuleSet} object describing rules on artifact versions to
   * ignore when considering updates.
   *
   * @see <a
   *     href="https://www.mojohaus.org/versions/versions-maven-plugin/version-rules.html#Using_the_ruleSet_element_in_the_POM">
   *     Using the ruleSet element in the POM</a>
   */
  @Parameter protected RuleSet ruleSet;

  /**
   * Allows specifying ignored versions directly as an alternative to providing the {@linkplain
   * #ruleSet} parameter; mainly created for {@code -D} property usage.
   *
   * <p>Example: {@code "1\.0\.1,.+-M.,.*-SNAPSHOT"}
   *
   * <p><em>Currently, this parameter will override the defined {@link #ruleSet}</em>
   */
  @Parameter(property = "maven.version.ignore")
  protected Set<String> ignoredVersions;

  /** If true, the plugin execution will be skipped. */
  @Parameter(property = "versions.skip", defaultValue = "false")
  protected boolean skip;

  /** (injected) map of {@link Wagon} instances per protocol */
  protected final Map<String, Wagon> wagonMap;

  /** An {@link ArtifactFactory} instance. */
  protected final ArtifactFactory artifactFactory;

  // --------------------- GETTER / SETTER METHODS ---------------------

  /**
   * Constructor used by Mojos that update versions.
   *
   * @param artifactFactory the artifact factory
   * @param repositorySystem the repository system
   * @param wagonMap map of wagon instances
   * @param changeRecorders map of change recorders
   * @throws MojoExecutionException when things go wrong
   */
  @Inject
  protected AbstractVersionsUpdaterMojo(
      ArtifactFactory artifactFactory,
      RepositorySystem repositorySystem,
      Map<String, Wagon> wagonMap,
      Map<String, ChangeRecorder> changeRecorders)
      throws MojoExecutionException {
    this.artifactFactory = artifactFactory;
    this.repositorySystem = repositorySystem;
    this.wagonMap = wagonMap;
    this.changeRecorders = changeRecorders;
  }

  /**
   * {@code true} if snapshots should be allowed when searching for newer versions of artifacts.
   * This can be overridden on a per-call basis by passing a non-{@code null} value to the {@link
   * #findLatestVersion(Artifact, VersionRange, Boolean, boolean)} method.
   *
   * <p>Default is {@code false} for all Mojos except {@code versions:use-latest-versions} and
   * {@code versions:use-latest-releases} where it is {@code true}
   *
   * @return {@code true} if snapshots should be allowed when searching for newer versions of
   *     artifacts.
   * @since 1.0-alpha-1
   * @see #findLatestVersion(Artifact, VersionRange, Boolean, boolean)
   */
  protected abstract boolean getAllowSnapshots();

  /**
   * Returns the configured {@link VersionsHelper} instance, creating it lazily.
   *
   * @return the {@link VersionsHelper}
   * @throws MojoExecutionException if the helper cannot be created
   */
  public synchronized VersionsHelper getHelper() throws MojoExecutionException {
    if (helper == null) {
      RuleService ruleService =
          new RulesServiceBuilder()
              .withMavenSession(session)
              .withWagonMap(wagonMap)
              .withServerId(serverId)
              .withRulesUri(rulesUri)
              .withRuleSet(ruleSet)
              .withIgnoredVersions(ignoredVersions)
              .withLog(getLog())
              .build();
      PomHelper pomHelper =
          new PomHelper(artifactFactory, new VersionsExpressionEvaluator(session, mojoExecution));
      helper =
          new DefaultVersionsHelper.Builder()
              .withArtifactFactory(artifactFactory)
              .withRepositorySystem(repositorySystem)
              .withLog(getLog())
              .withMavenSession(session)
              .withPomHelper(pomHelper)
              .withRuleService(ruleService)
              .build();
    }
    return helper;
  }

  /**
   * Gets the version of the project being processed.
   *
   * @return the version of the project being processed or {@code null} if no project is set
   */
  public String getVersion() {
    return getProject() == null ? null : getProject().getVersion();
  }

  // ------------------------ INTERFACE METHODS ------------------------

  // --------------------- Interface Mojo ---------------------

  /**
   * {@inheritDoc}
   *
   * @since 1.0-alpha-1
   */
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (skip) {
      getLog().info("Skipping execution");
      return;
    }
    validateInput();
    File outFile = project.getFile();
    process(outFile);
  }

  // -------------------------- OTHER METHODS --------------------------

  /**
   * Validates input parameters
   *
   * @throws MojoExecutionException thrown if any of input parameters is invalid
   */
  protected void validateInput() throws MojoExecutionException {}

  /**
   * Finds the latest version of the specified artifact that matches the version range.
   *
   * @param artifact The artifact.
   * @param versionRange The version range.
   * @param allowingSnapshots <code>null</code> for no override, otherwise the local override to
   *     apply.
   * @param usePluginRepositories Use plugin repositories
   * @return The latest version of the specified artifact that matches the specified version range
   *     or <code>null</code> if no matching version could be found.
   * @throws VersionRetrievalException If the artifact metadata could not be found.
   * @throws MojoExecutionException if something goes wrong.
   * @since 1.0-alpha-1
   */
  protected ArtifactVersion findLatestVersion(
      Artifact artifact,
      VersionRange versionRange,
      Boolean allowingSnapshots,
      boolean usePluginRepositories)
      throws MojoExecutionException, VersionRetrievalException {
    boolean includeSnapshots = allowingSnapshots != null ? allowingSnapshots : getAllowSnapshots();
    final ArtifactVersions artifactVersions =
        getHelper().lookupArtifactVersions(artifact, versionRange, usePluginRepositories);
    return artifactVersions.getNewestVersion(versionRange, null, includeSnapshots, false);
  }

  /**
   * Processes the specified file. This is an extension point to allow updating a file external to
   * the reactor.
   *
   * @param outFile The file to process.
   * @throws MojoExecutionException If things go wrong.
   * @throws MojoFailureException If things go wrong.
   */
  protected void process(File outFile) throws MojoExecutionException, MojoFailureException {
    try {
      MutableXMLStreamReader newPom = new MutableXMLStreamReader(outFile.toPath());
      update(newPom);
      if (newPom.isModified()) {
        if (generateBackupPoms) {
          File backupFile =
              new File(outFile.getParentFile(), outFile.getName() + ".versionsBackup");
          if (!backupFile.exists()) {
            getLog().debug("Backing up " + outFile + " to " + backupFile);
            Files.copy(outFile.toPath(), backupFile.toPath(), REPLACE_EXISTING);
          } else {
            getLog().debug("Leaving existing backup " + backupFile + " unmodified");
          }
        } else {
          getLog().debug("Skipping generation of backup file");
        }
        Charset cs =
            ofNullable(newPom.getEncoding()).map(Charset::forName).orElse(Charset.defaultCharset());
        try (Writer writer = Files.newBufferedWriter(outFile.toPath(), cs)) {
          writer.write(newPom.getSource());
        }
      }
      saveChangeRecorderResults();
    } catch (IOException e) {
      getLog().error(e);
    } catch (VersionRetrievalException | XMLStreamException e) {
      throw new MojoExecutionException(e.getMessage(), e);
    }
  }

  /**
   * Updates the pom.
   *
   * @param pom The pom to update.
   * @throws MojoExecutionException If things go wrong.
   * @throws MojoFailureException If things go wrong.
   * @throws javax.xml.stream.XMLStreamException If things go wrong.
   * @throws VersionRetrievalException if version retrieval goes wrong
   * @since 1.0-alpha-1
   */
  protected abstract void update(MutableXMLStreamReader pom)
      throws MojoExecutionException,
          MojoFailureException,
          XMLStreamException,
          VersionRetrievalException;

  /**
   * @param artifact The artifact.
   * @param currentVersion The current version of the artifact.
   * @param updateVersion The proposed new version of the artifact.
   * @return <code>true</code> if the update should be applied.
   * @since 1.0-alpha-1
   * @deprecated This method no longer supported. use shouldApplyUpdate( Artifact artifact, String
   *     currentVersion, ArtifactVersion updateVersion, Boolean forceUpdate )
   *     <p>Returns <code>true</code> if the update should be applied.
   */
  @Deprecated
  protected boolean shouldApplyUpdate(
      Artifact artifact, String currentVersion, ArtifactVersion updateVersion) {
    return shouldApplyUpdate(artifact, currentVersion, updateVersion, false);
  }

  /**
   * Returns <code>true</code> if the update should be applied.
   *
   * @param artifact The artifact.
   * @param currentVersion The current version of the artifact.
   * @param updateVersion The proposed new version of the artifact.
   * @param forceUpdate if true, LATEST and RELEASE versions will be overwritten with the real
   *     version
   * @return <code>true</code> if the update should be applied to the pom.
   * @since 2.9
   */
  protected boolean shouldApplyUpdate(
      Artifact artifact,
      String currentVersion,
      ArtifactVersion updateVersion,
      boolean forceUpdate) {
    getLog().debug("Proposal is to update from " + currentVersion + " to " + updateVersion);

    if (updateVersion == null) {
      getLog().warn("Not updating version: could not resolve any versions");
      return false;
    }

    if (forceUpdate) {
      getLog()
          .info(
              "Force update enabled. LATEST or RELEASE versions will be overwritten with real"
                  + " version");
      return true;
    }

    artifact.setVersion(updateVersion.toString());
    try {
      getHelper().resolveArtifact(artifact, false);
    } catch (ArtifactResolutionException | MojoExecutionException e) {
      getLog().warn("Not updating version: could not resolve " + artifact, e);
      return false;
    }

    if (currentVersion.equals(updateVersion.toString())) {
      getLog().info("Current version of " + artifact + " is the latest.");
      return false;
    }
    return true;
  }

  /**
   * Attempts to update the property to a newer version, if that exists
   *
   * @param pom pom to update
   * @param property property to update
   * @param version {@link PropertyVersions} object
   * @param currentVersion current version
   * @param allowDowngrade if downgrades should be allowed if snapshots are not allowed
   * @param unchangedSegment most major segment not to be changed
   * @return new version of the artifact, if the property was updated; {@code null} if there was no
   *     update
   * @throws XMLStreamException thrown from {@link MutableXMLStreamReader} if the update doesn't
   *     succeed
   * @throws InvalidVersionSpecificationException thrown if {@code unchangedSegment} doesn't match
   *     the version
   * @throws InvalidSegmentException thrown if {@code unchangedSegment} is invalid
   * @throws MojoExecutionException thrown if any other error occurs
   */
  protected ArtifactVersion updatePropertyToNewestVersion(
      MutableXMLStreamReader pom,
      Property property,
      PropertyVersions version,
      String currentVersion,
      boolean allowDowngrade,
      Optional<Segment> unchangedSegment)
      throws XMLStreamException,
          InvalidVersionSpecificationException,
          InvalidSegmentException,
          MojoExecutionException {
    ArtifactVersion winner =
        version.getNewestVersion(
            currentVersion,
            property,
            getAllowSnapshots(),
            this.reactorProjects,
            allowDowngrade,
            unchangedSegment);

    if (winner == null || currentVersion.equals(winner.toString())) {
      getLog()
          .info("Property ${" + property.getName() + "}: Leaving unchanged as " + currentVersion);
    } else if (PomHelper.setPropertyVersion(
        pom, version.getProfileId(), property.getName(), winner.toString())) {
      getLog()
          .info("Updated ${" + property.getName() + "} from " + currentVersion + " to " + winner);
      return winner;
    }

    return null;
  }

  /**
   * Configure and return the change recorder.
   *
   * @return The change recorder
   * @throws MojoExecutionException if something goes wrong.
   */
  protected ChangeRecorder getChangeRecorder() throws MojoExecutionException {
    ChangeRecorder changeRecorder = changeRecorders.get(changeRecorderFormat);
    if (changeRecorder == null) {
      throw new MojoExecutionException(
          "Only " + changeRecorders.keySet() + " formats are supported for change recordings");
    }
    return changeRecorder;
  }

  /**
   * Save all of the changes recorded by the change recorder.
   *
   * @throws IOException On I/O errors
   * @throws MojoExecutionException if something goes wrong.
   */
  protected void saveChangeRecorderResults() throws IOException, MojoExecutionException {

    this.getLog().debug("writing change record to " + this.changeRecorderOutputFile);
    getChangeRecorder()
        .writeReport(ofNullable(changeRecorderOutputFile).map(File::toPath).orElse(null));
  }
}
