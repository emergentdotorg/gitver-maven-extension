# Gitver Maven Extension

A Maven core extension that derives project versions from git: the most recent version tag and
the distance to it.

The extension rewrites the versions of the project, its parent, and every related project
referenced as a dependency or plugin. It writes the result to `.gitver-pom.xml` next to each
`pom.xml`, and by default attaches that file as the installed and deployed POM. Add
`.gitver-pom.xml` to your `.gitignore`.

## Requirements

- Java 17 or later
- `git` on the `PATH`; the extension runs it to read the repository

## Setup

Register the extension in `.mvn/extensions.xml`:

```xml
<extensions>
  <extension>
    <groupId>lol.wud.gitver</groupId>
    <artifactId>gitver-maven-extension</artifactId>
    <version>0.0.1</version>
  </extension>
</extensions>
```

## Version

The version is calculated from the most recent tag reachable from `HEAD` by following first
parents only (`git describe --tags --first-parent`). A leading `v` is ignored (`v1.2.3` or
`1.2.3`), and missing minor or patch numbers count as `0`.

| Git state                            | Version                                                     |
|--------------------------------------|-------------------------------------------------------------|
| on the tag, no uncommitted changes   | `major.minor.patch` of the tag, e.g. `1.2.3`                |
| past the tag, or uncommitted changes | the tag with `patch` incremented plus `-SNAPSHOT`, e.g. `1.2.4-SNAPSHOT` |
| no tag found                         | the version already in the pom                              |

Untracked files count as uncommitted changes.

## Environment variables

| Variable          | Description                                                                        |
|-------------------|------------------------------------------------------------------------------------|
| `GIT_TAG`         | Use this tag instead of the repository's, e.g. in CI builds of a detached head.    |
| `GITVER_DISABLE`  | Set to `true` to disable the extension.                                            |

## When versioning is skipped

The extension leaves a project unchanged, and logs why, when the pom is not inside a git work
tree or when `GITVER_DISABLE=true`. It fails the build when `git` can't be run, doesn't finish
within 30 seconds, or refuses to read the repository (for example, "dubious ownership" errors
that need `git config --global --add safe.directory <path>`).

## Logging

| Property                                  | Description                            |
|-------------------------------------------|----------------------------------------|
| `-Dorg.slf4j.simpleLogger.showLogName=true` | Show slf4j logger names in the maven log |

## Building

```
./mvnw verify              # unit tests
./mvnw verify -Prun-its    # plus the integration tests in src/it
./mvnw spotless:apply      # format the code (palantir-java-format)
```

The build fails when code is not formatted, so run `spotless:apply` before committing.

## Credits

Special thanks to the [qoomon/maven-git-versioning-extension](https://github.com/qoomon/maven-git-versioning-extension)
project for a large portion of the inspiration behind this project!
