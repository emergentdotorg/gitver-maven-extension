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
package org.emergent.gittle.maven.extension;

import java.util.Optional;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

@SuppressWarnings("UnusedReturnValue")
@Singleton
@Named("gittle-session")
@ToString
@EqualsAndHashCode
@RequiredArgsConstructor
public class GittleSessionHolder {

  @Setter @Nullable private GittleSession gittleSession;

  public Optional<GittleSession> getGittleSession() {
    return Optional.ofNullable(gittleSession);
  }

  public GittleSessionHolder clear() {
    return setGittleSession(null);
  }
}
