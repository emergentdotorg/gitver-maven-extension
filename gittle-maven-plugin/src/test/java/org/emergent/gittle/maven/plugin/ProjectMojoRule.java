package org.emergent.gittle.maven.plugin;

import java.io.File;
import java.io.FileReader;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.testing.AbstractMojoTestCase;
import org.apache.maven.plugin.testing.MojoRule;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public class ProjectMojoRule extends MojoRule implements BeforeEachCallback, AfterEachCallback {

  private final MyMojoTestCase testCase;

  public ProjectMojoRule() {
    this(new MyMojoTestCase() {});
  }

  private ProjectMojoRule(MyMojoTestCase testCase) {
    super(testCase);
    this.testCase = testCase;
  }

  @Override
  public MavenProject readMavenProject(File basedir) throws Exception {
    // Manual project instantiation is to avoid
    // Invalid repository system session: Local Repository Manager is not set.
    // when using default implementation.
    File pom = new File(basedir, "pom.xml");
    MavenXpp3Reader mavenReader = new MavenXpp3Reader();
    try (FileReader reader = new FileReader(pom)) {
      Model model = mavenReader.read(reader);
      model.setPomFile(pom);
      MavenProject mavenProject = new MavenProject(model);
      mavenProject.setFile(pom);
      return mavenProject;
    } catch (Exception e) {
      throw new RuntimeException("Couldn't read pom: " + pom);
    }
  }

  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    try {
      before();
    } catch (Error | RuntimeException e) {
      throw e;
    } catch (Throwable e) {
      throw new Error(e);
    }
  }

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    after();
  }

  @Override
  protected void before() throws Throwable {
    testCase.setUp();
  }

  @Override
  protected void after() {
    try {
      testCase.tearDown();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public abstract static class MyMojoTestCase extends AbstractMojoTestCase {

    @Override
    public void setUp() throws Exception {
      super.setUp();
    }

    @Override
    public void tearDown() throws Exception {
      super.tearDown();
    }
  }
}
