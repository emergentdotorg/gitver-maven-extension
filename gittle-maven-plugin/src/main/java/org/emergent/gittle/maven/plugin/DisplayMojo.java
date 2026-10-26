package org.emergent.gittle.maven.plugin;

import java.util.Map;
import javax.inject.Inject;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.shared.utils.logging.MessageUtils;
import org.emergent.gittle.core.Util;
import org.emergent.gittle.maven.extension.ExtensionUtil;
import org.emergent.gittle.maven.extension.GittleSession;
import org.emergent.gittle.maven.extension.GittleSessionHolder;

@Mojo(name = DisplayMojo.MOJO_NAME, defaultPhase = LifecyclePhase.VALIDATE)
public class DisplayMojo extends AbstractGittleMojo {

  public static final String MOJO_NAME = "display";
  @Inject GittleSessionHolder sessionHolder;

  @Override
  public void execute() {
    Log log = getLog();
    sessionHolder
        .getGittleSession()
        .map(GittleSession::getInfoProvider)
        .ifPresent(
            infoProvider -> {
              Map<String, String> props = ExtensionUtil.getPropertiesFromMetadatas(infoProvider);
              String msg =
                  "Printing properties of project "
                      + MessageUtils.buffer()
                          .mojo(ExtensionUtil.toGav(mavenProject))
                          .a(Util.join(props));
              log.info(msg);
            });
  }
}
