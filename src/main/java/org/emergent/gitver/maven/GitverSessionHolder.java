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
package org.emergent.gitver.maven;

import java.util.Optional;
import javax.inject.Named;
import javax.inject.Singleton;
import org.jspecify.annotations.Nullable;

@Singleton
@Named("gitver-session")
public class GitverSessionHolder {

  @Nullable private GitverSession gitverSession;

  public Optional<GitverSession> getGitverSession() {
    return Optional.ofNullable(gitverSession);
  }

  public void setGitverSession(@Nullable GitverSession gitverSession) {
    this.gitverSession = gitverSession;
  }

  public void clear() {
    setGitverSession(null);
  }
}
