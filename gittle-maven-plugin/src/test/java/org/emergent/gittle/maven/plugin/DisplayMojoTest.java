package org.emergent.gittle.maven.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.shared.utils.logging.MessageUtils;
import org.emergent.gittle.core.GAV;
import org.emergent.gittle.core.Util;
import org.emergent.gittle.maven.extension.ExtensionUtil;
import org.emergent.gittle.maven.extension.GittleSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DisplayMojoTest extends AbstractMojoTest {

  @Test
  public void testExecute() throws Exception {
    Path baseDir = Path.of("target/test-classes/project-to-test");
    assertThat(baseDir).as("Project Basedir").isNotNull().exists();

    // Path oldPomPath = baseDir.resolve("pom.xml");
    // assertThat(oldPomPath).as("POM file").isNotNull().exists();
    //
    // // todo: config param?
    // String newName = Util.GITTLE_POM_XML;
    //
    // Path newPomPath = oldPomPath.resolveSibling(newName);
    // Files.writeString(newPomPath, "<project></project>", CREATE, TRUNCATE_EXISTING);
    // // assertThat(newPom).as("New pom file").isNotNull().exists();
    //
    // UpdateMojo mojo = rule.lookupConfiguredMojo(baseDir.toFile(), UpdateMojo.MOJO_NAME);
    // assertThat(mojo).isNotNull();

    DisplayMojo mojo = rule.lookupConfiguredMojo(baseDir.toFile(), DisplayMojo.MOJO_NAME);
    assertThat(mojo).isNotNull();
    TestLog testLog = new TestLog();
    mojo.setLog(testLog);
    MavenSession mavenSession = mojo.getMavenSession();
    mavenSession.getRequest().setMultiModuleProjectDirectory(baseDir.toFile());

    GittleSession gittleSession = ExtensionUtil.createGittleSession(mavenSession);
    mojo.sessionHolder.setGittleSession(gittleSession);
    mojo.execute();

    Log log = mojo.getLog();
    // System.out.printf("%s%n", Objects.hashCode(log));
    // log.info("hi");

    assertThat(log).isInstanceOfAny(TestLog.class);

    GAV gav =
        GAV.builder()
            .setGroupId("org.emergent.test.gittle")
            .setArtifactId("gittle-plugin-test")
            .setVersion(mojo.getMavenProject().getVersion())
            .build();

    String expectedVersion = "0.0.0-NOT_A_GIT_REPOSITORY";
    Map<String, String> props =
        new TreeMap<>(
            Map.of(
                "gittle.plugin-version", "@gittle.calculated_version@",
                "gittle.used_version", expectedVersion,
                "revision", expectedVersion,
                "gittle.version", expectedVersion));

    pname.stream().map("gittle.%s"::formatted).forEach(k -> props.putIfAbsent(k, ""));
    // assertThat(testLog.getMessages()).isNotEmpty()
    //     .allMatch(s -> s.startsWith("Printing properties of project "));
    //

    // verifyNoInteractions(log);

    String expected =
        "Printing properties of project " + MessageUtils.buffer().mojo(gav).a(Util.join(props));
    assertThat(testLog.getMessages()).isNotEmpty().element(0).isEqualTo(expected);
    // .allMatch(s -> s.startsWith(expected));
  }

  private static final List<String> pname =
      Stream.of(
              "all_annotated_tags",
              "all_lightweight_tags",
              "all_tags",
              "all_version_annotated_tags",
              "all_version_lightweight_tags",
              "all_version_tags",
              "annotated",
              "base_commit_on_head",
              "base_tag",
              "base_tag_type",
              "base_version",
              "branch_name",
              "calculated_version",
              "commit_distance",
              "commit_distance_to_root",
              "commit_iso_timestamp",
              "commit_timestamp",
              "current_version_major",
              "current_version_minor",
              "current_version_patch",
              "detached_head",
              "dirty",
              "dirty_text",
              "git_sha1_8",
              "git_sha1_full",
              "head_annotated_tags",
              "head_commit_datetime",
              "head_commiter_email",
              "head_committer_name",
              "head_lightweight_tags",
              "head_tags",
              "head_version_annotated_tags",
              "head_version_lightweight_tags",
              "head_version_tags",
              "next_major_version",
              "next_minor_version",
              "next_patch_version",
              "plugin-version",
              "provided_branch_name",
              "qualified_branch_name",
              "used_version",
              "version")
          .sorted()
          .distinct()
          .toList();
}
