/*
 * Copyright (C) 2016 Matthieu Brouillard [http://oss.brouillard.fr/jgitver-maven-plugin] (matthieu@brouillard.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.emergent.gittle.maven.util;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.MavenExecutionException;
import org.codehaus.plexus.logging.Logger;
import org.emergent.gittle.core.Throwing;
import org.emergent.gittle.maven.extension.LoggerEx;
import org.emergent.gittle.maven.extension.LoggerEx.PlexusImpl;
import org.emergent.gittle.maven.extension.LoggerEx.SilentImpl;
import org.jspecify.annotations.Nullable;
import org.simpleframework.xml.Serializer;
import org.simpleframework.xml.convert.AnnotationStrategy;
import org.simpleframework.xml.core.Persister;
import org.simpleframework.xml.strategy.Strategy;

public class ConfigurationLoader {
  private static final String NAMESPACE_1_0_0_beta =
      "http://jgitver.github.io/maven/configuration/1.0.0-beta";
  private static final String NAMESPACE_1_0_0 =
      "http://jgitver.github.io/maven/configuration/1.0.0";

  private static final String EXTENSION_PREFIX = "gittle";
  private final LoggerEx logger;

  private final List<File> configurationFiles = new ArrayList<>();

  public ConfigurationLoader(File rootDirectory, LoggerEx logger) {
    this.logger = logger;

    String cliConfigFile = System.getProperty(EXTENSION_PREFIX + ".config");
    if (cliConfigFile != null) {
      logger.debug("jgitver configuration file overridden with {}", cliConfigFile);
      configurationFiles.add(new File(cliConfigFile));
    }
    File dotMvnDir = new File(rootDirectory, ".mvn");
    File cfgXmlFile = new File(dotMvnDir, "jgitver.config.xml");

    configurationFiles.add(cfgXmlFile);
  }

  public Configuration load() throws MavenExecutionException {
    for (File cfgFile : configurationFiles) {
      logger.debug("trying to load configuration from: {}", cfgFile);
      Configuration c = loadFromFile(cfgFile, logger);
      if (c != null) {
        logger.info("Using gittle configuration file: {}", cfgFile);
        return c;
      }
    }
    logger.info("No suitable configuration file found, using defaults");
    return new Configuration();
  }

  public static Configuration loadFromRoot(File rootDirectory) {
    return loadFromRoot(rootDirectory, SilentImpl.INSTANCE);
  }

  public static Configuration loadFromRoot(File rootDirectory, Logger logger) {
    return loadFromRoot(rootDirectory, new PlexusImpl(logger));
  }

  /**
   * Loads a Configuration object from the root directory.
   *
   * @param rootDirectory the root directory of the maven project
   * @param logger the logger to report activity
   * @return a non null Configuration object from the file $rootDirectory/.mvn/jgitver.config.xml or
   *     a default one with default values if the configuration file does not exist
   */
  static Configuration loadFromRoot(File rootDirectory, LoggerEx logger) {
    return Throwing.get(() -> new ConfigurationLoader(rootDirectory, logger).load());
    //        File extensionMavenCoreDirectory = new File(rootDirectory, ".mvn");
    //        File configurationXml = new File(extensionMavenCoreDirectory, "jgitver.config.xml");
    //        if (!configurationXml.canRead()) {
    //            logger.debug("no configuration file found under " + configurationXml + ", looking
    // under backwards-compatible file name");
    //            configurationXml = new File(extensionMavenCoreDirectory, "jgtiver.config.xml");
    //            if (!configurationXml.canRead()) {
    //                logger.debug("no configuration file found under " + configurationXml + ",
    // using defaults");
    //                return new Configuration();
    //            }
    //        }
    //
    //        try {
    //            logger.info("using jgitver configuration file: " + configurationXml);
    //            Configuration c = loadFromFile(configurationXml);
    //            return c;
    //        } catch (Exception ex) {
    //            throw new MavenExecutionException("cannot read configuration file " +
    // configurationXml, ex);
    //        }
  }

  private static @Nullable Configuration loadFromFile(File configurationXml)
      throws MavenExecutionException {
    return loadFromFile(configurationXml, SilentImpl.INSTANCE);
  }

  private static @Nullable Configuration loadFromFile(File xmlFile, LoggerEx logger)
      throws MavenExecutionException {
    Strategy strategy = new AnnotationStrategy();
    Serializer serializer = new Persister(strategy);

    if (!xmlFile.exists()) {
      logger.debug("gittle configuration file {} does not exists, skipping it", xmlFile);
      return null;
    }

    if (!xmlFile.canRead()) {
      logger.warn("gittle configuration file {} cannot be read, skipping it", xmlFile);
      return null;
    }

    try {
      return serializer.read(Configuration.class, xmlFile);
    } catch (Exception e) {
      throw new MavenExecutionException("failure reading configuration from: " + xmlFile, e);
    }
  }
}
