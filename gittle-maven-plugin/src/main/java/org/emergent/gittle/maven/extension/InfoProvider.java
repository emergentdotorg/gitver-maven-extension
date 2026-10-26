package org.emergent.gittle.maven.extension;

import fr.brouillard.oss.jgitver.Version;
import fr.brouillard.oss.jgitver.metadata.Metadatas;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public record InfoProvider(Version version, Map<Metadatas, String> metadata) {

  public InfoProvider {
    metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
  }

  public Optional<String> meta(Metadatas meta) {
    return Optional.ofNullable(metadata.get(meta));
  }

  public boolean isDirty() {
    return meta(Metadatas.DIRTY).map(Boolean::parseBoolean).orElse(Boolean.FALSE);
  }
}
