package org.emergent.gitver.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.building.Source;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelProcessor;
import org.apache.maven.model.building.ModelProcessor;

@Slf4j
@Singleton
@Named("core-default")
public class GitverModelProcessor implements ModelProcessor {

  @Inject private GitverSessionHolder sessionHolder;

  private ModelProcessor delegate = new DefaultModelProcessor();

  @Inject
  void setDelegate(List<ModelProcessor> modelProcessors) {
    this.delegate =
        modelProcessors.stream()
            // Avoid circular dependency
            .filter(processor -> processor != this)
            .findFirst()
            .orElseGet(DefaultModelProcessor::new);
  }

  @Override
  public File locatePom(File projectDirectory) {
    return delegate.locatePom(projectDirectory);
  }

  @Override
  public Model read(File input, Map<String, ?> options) throws IOException {
    return processModel(delegate.read(input, options), options);
  }

  @Override
  public Model read(Reader input, Map<String, ?> options) throws IOException {
    return processModel(delegate.read(input, options), options);
  }

  @Override
  public Model read(InputStream input, Map<String, ?> options) throws IOException {
    return processModel(delegate.read(input, options), options);
  }

  public Model processModel(Model projectModel, Map<String, ?> options) throws IOException {
    // clone model before return to prevent concurrency issues
    return processModel0(projectModel, options).clone();
  }

  private Model processModel0(Model projectModel, Map<String, ?> options) throws IOException {
    Source pomSource = (Source) options.get(ModelProcessor.SOURCE);
    if (pomSource != null) {
      projectModel.setPomFile(new File(pomSource.getLocation()));
    }
    return projectModel;
  }
}
