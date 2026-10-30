# Gitver Maven Extension

A Maven core extension that derives project versions from git: the current branch, tag or
commit selects a version format, which is filled in from git state such as the most recent tag
and the distance to it.

The extension rewrites the versions of the project, its parent, and every related project
referenced as a dependency or plugin. It writes the result to `.gitver-pom.xml` next to each
`pom.xml`, and by default attaches that file as the installed and deployed POM. The source
`pom.xml` is left untouched unless `updateSourcePom` is enabled.

## Setup

Register the extension in `.mvn/extensions.xml`:

```xml
<extensions>
  <extension>
    <groupId>org.emergent.gitver</groupId>
    <artifactId>gitver-maven-extension</artifactId>
    <version>0.2.0</version>
  </extension>
</extensions>
```

Without further configuration the built-in defaults apply:

| Git state                       | Version                          |
|---------------------------------|----------------------------------|
| detached on a tag `v<version>`  | `<version>`                      |
| on any branch                   | `${describe.tag.version.maven}`  |
| detached on any other commit    | `${commit}-SNAPSHOT`             |

## Configuration

Configuration is read from `.mvn/gitver-config.xml` (see
`src/main/resources/schema/gitver-configuration-v1.0.0.xsd`):

```xml
<configuration xmlns="http://emergentdotorg.github.io/gitver/configuration/1.0.0">
  <refs>
    <ref>
      <type>tag</type>
      <pattern><![CDATA[v(?<version>.*)]]></pattern>
      <version>${ref.version}</version>
    </ref>
    <ref>
      <type>branch</type>
      <pattern>(main|release-.*)</pattern>
      <version>${describe.tag.version.maven}</version>
    </ref>
    <ref>
      <type>branch</type>
      <pattern>.+</pattern>
      <version>${describe.tag.version.next}-${ref.slug}-SNAPSHOT</version>
      <properties>
        <my.property>${value}-${commit.short}</my.property>
      </properties>
    </ref>
  </refs>
</configuration>
```

The first `<ref>` matching the git state is used:

- `tag` matches when the head is detached (or `considerTagsOnBranches` is set) and one of its tags
  matches `pattern`.
- `branch` matches when the head is on a branch whose name matches `pattern`.
- `commit` matches any detached head.

Set `appendDefaults="true"` on `<refs>` to append the built-in refs after your own.

| Element                   | Where          | Default       | Description                                                                 |
|---------------------------|----------------|---------------|-----------------------------------------------------------------------------|
| `disable`                 | top            | `false`       | Disables the extension.                                                     |
| `considerTagsOnBranches`  | top            | `false`       | Lets `tag` refs match while on a branch.                                    |
| `projectVersionPattern`   | top            |               | Regex applied to the project version; named groups become `${version.<group>}`. |
| `relatedProjects`         | top            |               | Extra `<project>` (`groupId`, `artifactId`) whose versions are rewritten.   |
| `version`                 | top, ref       |               | Version format. Without it the version is left alone.                       |
| `tagPattern`              | top, ref       | `v(?<ver>.*)` | Tags considered by `${describe...}`.                                        |
| `followFirstParentOnly`   | top, ref       | `true`        | Only follow first parents when searching for the most recent tag.          |
| `updateAttachedPom`       | top, ref       | `true`        | Install and deploy `.gitver-pom.xml` instead of `pom.xml`.                  |
| `updateSourcePom`         | top, ref       | `false`       | Overwrite `pom.xml` with the versioned POM.                                 |
| `properties`              | ref            |               | Formats for existing project properties; `${value}` is the current value.  |
| `userProperties`          | ref            |               | Formats for properties added to the Maven session.                          |

Options set at the top level are the defaults for every ref.

## Placeholders

Placeholders may use shell-style modifiers: `${key:-default}` and `${key-default}` substitute a
default for an empty or missing value, `${key:+alt}` and `${key+alt}` substitute `alt` for a present
value.

| Placeholder                                         | Value                                                           |
|-----------------------------------------------------|-----------------------------------------------------------------|
| `version`                                           | The original version being replaced.                           |
| `version.core`, `.major`, `.minor`, `.patch`, `.build`, `.label` | Parts of the original version, e.g. `1.2.3.4-rc1`. |
| `version.major.next`, `.minor.next`, `.patch.next`, `.build.next`, `.label.next` | The part incremented.  |
| `version.label.prefixed`                            | `-<label>`, or empty.                                           |
| `version.next`                                      | The original version with its last part incremented.           |
| `version.<group>`                                   | A named group of `projectVersionPattern`.                       |
| `ref`, `ref.slug`                                   | The matched branch, tag or commit; `/` replaced by `-` in slug. |
| `ref.<group>`, `ref.<group>.slug`                   | A group of the ref `pattern`.                                   |
| `commit`, `commit.short`                            | The head commit hash.                                           |
| `commit.timestamp`                                  | Commit time in epoch seconds.                                   |
| `commit.timestamp.year`, `.year.2digit`, `.month`, `.day`, `.hour`, `.minute`, `.second` | Commit time parts (UTC). |
| `commit.timestamp.datetime`, `commit.timestamp.iso` | `yyyyMMdd.HHmmss` and ISO-8601 commit time.                     |
| `build.timestamp...`                                | As `commit.timestamp...`, for the build start (local time).    |
| `dirty`, `dirty.snapshot`                           | `.dirty` / `-SNAPSHOT` when the work tree has changes.          |
| `describe`                                          | `<tag>-<distance>-g<commit>`, like `git describe`.              |
| `describe.tag`, `describe.distance`                 | The most recent tag matching `tagPattern` and commits since.   |
| `describe.distance.snapshot`, `describe.snapshot`   | `-SNAPSHOT` when past the tag, or past the tag or dirty.        |
| `describe.tag.<group>`, `describe.tag.<group>.slug` | A group of `tagPattern`.                                        |
| `describe.tag.version...`                           | As `version...`, for the version within the tag.               |
| `describe.tag.version.<part>.plus.describe.distance` | `patch`, `patch.next`, `build`, `build.next`, `label`, `label.next` plus the distance. |
| `describe.tag.version.maven`                        | `major.minor.patch` of the tag; past the tag or dirty, the last part is incremented and `-SNAPSHOT` appended. |
| `describe.tag.version.next.snapshot`                | `describe.tag.version.next` followed by `describe.snapshot`.    |
| `property.<name>`                                   | A Maven user property (`-D<name>=...`).                         |
| `env.<name>`                                        | An environment variable.                                        |

## Command options

Options can be given as Maven user properties (`-D<name>=<value>`), as environment variables, or
as JVM system properties, in that order of precedence.

| Property                      | Environment variable            | Description                                       |
|-------------------------------|---------------------------------|---------------------------------------------------|
| `git.branch`, `git.tag`       | `VERSIONING_GIT_BRANCH`, `VERSIONING_GIT_TAG` | Use this branch and/or tag instead of the repository's. |
| `git.ref`                     | `VERSIONING_GIT_REF`            | Use this ref, e.g. `refs/heads/main` or `refs/tags/v1.0.0`. |
| `versioning.disable`          | `VERSIONING_DISABLE`            | Disables the extension.                           |
| `versioning.updateSourcePom`  | `VERSIONING_UPDATE_SOURCE_POM`  | Overrides `updateSourcePom`.                      |
| `versioning.updateAttachedPom`| `VERSIONING_UPDATE_ATTACHED_POM`| Overrides `updateAttachedPom`.                    |
| `versioning.configFile`       | `VERSIONING_CONFIG_FILE`        | Configuration file name within `.mvn`.            |

When the head is detached and none of these are given, the branch or tag is taken from the
environment of GitHub Actions, GitLab CI, CircleCI or Jenkins, provided the CI build is for the
checked out commit.

## Project properties

Each versioned project gets the following properties, unless already defined: `git.worktree`,
`git.commit`, `git.commit.short`, `git.commit.timestamp`, `git.commit.timestamp.datetime` (ISO-8601),
`git.ref` and `git.ref.slug`.

## Logging

| Property                                | Description                          |
|-----------------------------------------|--------------------------------------|
| org.slf4j.simpleLogger.showLogName=true | Show slf4j logger names in maven log |
