package org.emergent.gitver.maven;

import static java.lang.Math.ceil;
import static java.lang.Math.floor;
import static java.lang.Math.max;
import static org.apache.maven.shared.utils.StringUtils.repeat;
import static org.apache.maven.shared.utils.logging.MessageUtils.buffer;

import org.apache.maven.model.ModelBase;
import org.apache.maven.model.Profile;

/** Formatting of the console headers logged while processing models. */
final class LogHeaders {

  private LogHeaders() {}

  static String extension(Coords extensionCoords) {
    String extension = extensionCoords.toString();
    String metaInfo = "[core extension]";

    String plainLog = extension + " " + metaInfo;
    String formattedLog = buffer().a(" ").mojo(extension).a(" ").strong(metaInfo).a(" ").build();

    return padLogHeaderPadding(plainLog, formattedLog);
  }

  private static String padLogHeaderPadding(String plainLog, String formattedLog) {
    String pad = "-";
    int padding = max(6, 72 - 2 - plainLog.length());
    int paddingLeft = (int) floor(padding / 2.0);
    int paddingRight = (int) ceil(padding / 2.0);
    return buffer()
        .strong(repeat(pad, paddingLeft))
        .a(formattedLog)
        .strong(repeat(pad, paddingRight))
        .build();
  }

  static String project(Coords projectCoords) {
    String project = projectCoords.getProjectId();
    return buffer().project(project).build();
  }

  static String section(String title, ModelBase model) {
    String header = title + ":";
    if (model instanceof Profile profile) {
      header = buffer().strong("profile " + profile.getId() + " ") + header;
    }
    return header;
  }
}
