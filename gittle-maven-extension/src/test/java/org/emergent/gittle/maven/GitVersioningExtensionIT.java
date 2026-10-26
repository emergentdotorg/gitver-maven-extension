package org.emergent.gittle.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.emergent.gittle.commons.GitRefType.BRANCH;
import static org.emergent.gittle.commons.GitRefType.TAG;
import static org.emergent.gittle.maven.Constants.GIT_VERSIONING_POM_NAME;
import static org.emergent.gittle.maven.MavenUtil.readModel;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import org.apache.maven.model.Model;
import org.apache.maven.shared.verifier.Verifier;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.emergent.gittle.commons.GitRefType;
import org.emergent.gittle.maven.Configuration.PatchDescription;
import org.emergent.gittle.maven.Configuration.RefPatchDescription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirFactory;

class GitVersioningExtensionIT {

  static class TempITFactory implements TempDirFactory {

    private static final String TEMP_DIR_PREFIX = "junit-";

    public TempITFactory() {}

    @Override
    public Path createTempDirectory(AnnotatedElementContext elCtx, ExtensionContext extCtx)
        throws IOException {
      Path destParentDir = Path.of("target/it").toAbsolutePath();
      Files.createDirectories(destParentDir);
      return Files.createTempDirectory(destParentDir, TEMP_DIR_PREFIX);
    }
  }

  @TempDir(factory = TempITFactory.class, cleanup = CleanupMode.ON_SUCCESS)
  Path projectDir;

  Model pomModel;

  @BeforeEach
  void setup() {
    pomModel =
        new Model() {
          {
            setModelVersion("4.0.0");
            setGroupId("test");
            setArtifactId("test-artifact");
            setVersion("0.0.0");
          }
        };
  }

  @Test
  void noGitVersioning() throws Exception {
    // Given
    writeModel(projectDir.resolve("pom.xml").toFile(), pomModel);
    writeExtensionsFile(projectDir);
    writeExtensionConfigFile(projectDir, new Configuration());

    // When
    Verifier verifier = getVerifier(projectDir);
    verifier.addCliArgument("verify");
    verifier.execute();

    // Then
    System.err.println(
        String.join(
            "\n", verifier.loadFile(verifier.getBasedir(), verifier.getLogFileName(), false)));
    verifier.verifyErrorFreeLog();
    String expectedVersion = "0.0.0";
    verifier.verifyTextInLog("Building " + pomModel.getArtifactId() + " " + expectedVersion);
    verifier.verifyTextInLog("[WARNING] skip - project is not part of a git repository");
    verifier.verifyFileNotPresent(GIT_VERSIONING_POM_NAME);
  }

  private static Verifier getVerifier(Path projectDir) throws Exception {
    Path settingsFile = writeIsolatedSettings(projectDir);
    Verifier verifier =
        new Verifier(projectDir.toFile().getAbsolutePath(), settingsFile.toString(), true);
    verifier.addCliArguments("-s", settingsFile.toString());
    return verifier;
  }

  private static Path writeIsolatedSettings(Path projectDir) throws IOException {
    Path settingsFile = projectDir.resolve("test-settings.xml");
    Path localRepo = Paths.get(System.getProperty("user.home"), ".m2", "repository");
    Files.writeString(
        settingsFile,
        // language=XML
        """
        <settings>
          <localRepository>%s</localRepository>
        </settings>
        """
            .formatted(localRepo),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
    return settingsFile;
  }

  @Test
  void disableVersioning_cliOption() throws Exception {
    // Given
    try (Git git = Git.init().setInitialBranch("master").setDirectory(projectDir.toFile()).call()) {
      git.commit().setMessage("initial commit").setAllowEmpty(true).call();

      writeModel(projectDir.resolve("pom.xml").toFile(), pomModel);
      writeExtensionsFile(projectDir);

      writeExtensionConfigFile(projectDir, new Configuration());

      // When
      Verifier verifier = getVerifier(projectDir);
      verifier.addCliArgument("-Dversioning.disable");
      verifier.addCliArguments("verify");
      verifier.execute();

      // Then
      System.err.println(
          String.join(
              "\n", verifier.loadFile(verifier.getBasedir(), verifier.getLogFileName(), false)));
      verifier.verifyErrorFreeLog();
      verifier.verifyTextInLog("skip - versioning is disabled by command option");
      String expectedVersion = pomModel.getVersion();
      verifier.verifyTextInLog("Building " + pomModel.getArtifactId() + " " + expectedVersion);
      verifier.verifyFileNotPresent(GIT_VERSIONING_POM_NAME);
    }
  }

  @Test
  void revVersioning_noCommit() throws Exception {

    try (Git git = Git.init().setInitialBranch("master").setDirectory(projectDir.toFile()).call()) {
      // Given
      writeModel(projectDir.resolve("pom.xml").toFile(), pomModel);
      writeExtensionsFile(projectDir);
      writeExtensionConfigFile(
          projectDir,
          new Configuration() {
            {
              rev = createRevVersionDescription();
            }
          });

      // When
      Verifier verifier = getVerifier(projectDir);
      verifier.addCliArgument("verify");
      verifier.execute();

      // Then
      System.err.println(
          String.join(
              "\n", verifier.loadFile(verifier.getBasedir(), verifier.getLogFileName(), false)));
      verifier.verifyErrorFreeLog();
      String expectedVersion = "0000000000000000000000000000000000000000";
      verifier.verifyTextInLog("Building " + pomModel.getArtifactId() + " " + expectedVersion);

      Model gitVersionedPomModel = readModel(projectDir.resolve(GIT_VERSIONING_POM_NAME).toFile());
      assertThat(gitVersionedPomModel.getVersion()).isEqualTo(expectedVersion);
    }
  }

  @Test
  void revVersioning() throws Exception {

    try (Git git = Git.init().setInitialBranch("master").setDirectory(projectDir.toFile()).call()) {
      // Given
      RevCommit givenCommit = git.commit().setMessage("initial commit").setAllowEmpty(true).call();

      writeModel(projectDir.resolve("pom.xml").toFile(), pomModel);
      writeExtensionsFile(projectDir);
      writeExtensionConfigFile(
          projectDir,
          new Configuration() {
            {
              rev = createRevVersionDescription();
            }
          });

      // When
      Verifier verifier = getVerifier(projectDir);
      verifier.addCliArgument("verify");
      verifier.execute();

      // Then
      System.err.println(
          String.join(
              "\n", verifier.loadFile(verifier.getBasedir(), verifier.getLogFileName(), false)));
      verifier.verifyErrorFreeLog();
      String expectedVersion = givenCommit.getName();
      verifier.verifyTextInLog("Building " + pomModel.getArtifactId() + " " + expectedVersion);

      Model gitVersionedPomModel = readModel(projectDir.resolve(GIT_VERSIONING_POM_NAME).toFile());
      assertThat(gitVersionedPomModel.getVersion()).isEqualTo(expectedVersion);
    }
  }

  @Test
  void branchVersioning() throws Exception {

    try (Git git =
        Git.init().setInitialBranch("feature/test").setDirectory(projectDir.toFile()).call()) {
      // Given
      git.commit().setMessage("initial commit").setAllowEmpty(true).call();

      writeModel(projectDir.resolve("pom.xml").toFile(), pomModel);
      writeExtensionsFile(projectDir);
      writeExtensionConfigFile(
          projectDir,
          new Configuration() {
            {
              refs.list.add(createBranchVersionDescription());
            }
          });

      // When
      Verifier verifier = getVerifier(projectDir);
      verifier.addCliArgument("verify");
      verifier.execute();

      // Then
      System.err.println(
          String.join(
              "\n", verifier.loadFile(verifier.getBasedir(), verifier.getLogFileName(), false)));
      verifier.verifyErrorFreeLog();
      String expectedVersion = "feature-test-gitVersioning";
      verifier.verifyTextInLog("Building " + pomModel.getArtifactId() + " " + expectedVersion);

      Model gitVersionedPomModel = readModel(projectDir.resolve(GIT_VERSIONING_POM_NAME).toFile());
      assertThat(gitVersionedPomModel.getVersion()).isEqualTo(expectedVersion);
    }
  }

  private Path writeExtensionsFile(Path projectDir) throws IOException {
    Path mvnDotDir = Files.createDirectories(projectDir.resolve(".mvn"));
    return Files.writeString(
        mvnDotDir.resolve("extensions.xml"),
        // language=XML
        """
        <extensions>
          <extension>
            <groupId>org.emergent.gittle.maven</groupId>
            <artifactId>gittle-maven-extension</artifactId>
            <version>%s</version>
          </extension>
        </extensions>
        """
            .formatted(MavenUtil.gittleCoords().getVersion()),
        StandardCharsets.UTF_8,
        StandardOpenOption.WRITE,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  private PatchDescription createRevVersionDescription() {
    PatchDescription versionDescription = new PatchDescription();
    versionDescription.version = "${commit}";
    return versionDescription;
  }

  private RefPatchDescription createBranchVersionDescription() {
    return createVersionDescription(BRANCH, "${ref}-gitVersioning");
  }

  private RefPatchDescription createTagVersionDescription() {
    return createVersionDescription(TAG, "${ref}-gitVersioning");
  }

  private RefPatchDescription createVersionDescription(GitRefType type, String format) {
    RefPatchDescription refVersionDescription = new RefPatchDescription();
    refVersionDescription.type = type;
    refVersionDescription.pattern = ".*";
    refVersionDescription.version = format;
    return refVersionDescription;
  }

  private Path writeExtensionConfigFile(Path projectDir, Configuration config) throws Exception {
    Path mvnDotDir = Files.createDirectories(projectDir.resolve(".mvn"));
    Path configFile = mvnDotDir.resolve("gittle.config.xml");
    new XmlMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .writeValue(configFile.toFile(), config);
    System.err.printf(
        "Config file (useRevision=%s): %s%n%s%n%n",
        config.useRevision,
        configFile.toAbsolutePath(),
        Files.readString(configFile, StandardCharsets.UTF_8));
    return configFile;
  }

  private Model writeModel(File pomFile, Model pomModel) throws IOException {
    MavenUtil.writeModel(pomFile, pomModel);
    return pomModel;
  }
}
