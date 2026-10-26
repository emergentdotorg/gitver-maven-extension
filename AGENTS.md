# AGENTS.md

This file provides guidance to AI agents when working with code in this repository.

## Overview

Gittle is a Maven **core extension** that sets the `revision` property (and thus project versions) from Git history (tags, branch, commit count, hash, dirty state), with no changes to the project POMs beyond using `${revision}`. See `README.md` for user-facing configuration and the version pattern token table.

## Layout

- Root `pom.xml` (`gittle-parent`) lists only `gittle-maven-extension` as a module. Java 17, Lombok, version comes from `${revision}` (default `Z-LATEST-SNAPSHOT`).
- `gittle-maven-extension/` — the active module. Package `org.emergent.gittle.{commons,maven}`.
- `gittle-maven-plugin/` — **not in the reactor**; legacy code forked from jgitver-maven-plugin (flatten/versions mojos, jgitver XSDs). Don't expect it to build with the root; treat as reference unless asked.
- `gittle-dependencies-bom/` — only a `target/` dir, nothing tracked.
- The repo dogfoods itself: `.mvn/extensions.xml` has the extension commented out; `.mvn/gittle.config.xml` and `.mvn/gittle-maven-extension.properties` (`gittle.releaseBranchRegex`) are the repo's own config.

## Commands

Use the wrapper (`./mvnw`) from the repo root:

- Build + unit tests: `./mvnw clean verify`
- Full CI build including invoker integration tests: `./mvnw -B -ntp -e clean verify -Prun-its -U`
- Single unit test: `./mvnw -pl gittle-maven-extension test -Dtest=GitSituationTest` (add `#method` for one method)
- Failsafe ITs (`*IT`) run in `verify`; `GitVersioningExtensionIT` is explicitly excluded from the default failsafe execution.
- Invoker ITs live in `gittle-maven-extension/src/it/<case>/` (each with `pom.xml`, `setup.groovy` to create a git repo/tags, `verify.groovy` to assert). They're cloned to `target/its`, run with `-Drevision=0.0.1-SNAPSHOT` against `target/local-repo`, and only run under `-Prun-its`. `.envrc` sets `GIT_CEILING_DIRECTORIES` to `target/its` so the nested test repos don't resolve to this repo's git.

## Architecture

Flow inside Maven (see `gittle-maven-extension/docs/MavenGitVersioningExtension.png`):

1. `GittleMavenExtension` (`AbstractMavenLifecycleParticipant`) — `afterSessionStart` builds a `GittleSession` stored in the injected singleton `GittleSessionHolder`; `afterProjectsRead` records each project's GAV/properties; `afterSessionEnd` clears it.
2. `GittleModelProcessor` (replaces Maven's `ModelProcessor`) — intercepts POM reading, resolves the version, and rewrites the model so `${revision}` resolves. It also handles writing the flattened/rewritten POM and cross-module parent/dependency version fixups. This is the largest and most intricate class.
3. `GittleSession` / `Configuration` / `BuildProperties` — session state plus config loading. Config comes from `.mvn/gittle-maven-extension.properties` (e.g. `gittle.versionPattern`, `gittle.releaseBranchRegex`) and an XML config validated by `src/main/resources/schema/gittle-configuration-v1.0.0.xsd`, with defaults in `schema/gittle-defaults.xml`.
4. `commons/` — Git logic independent of Maven: `GitUtil` (JGit), `GitSituation` (branch/hash/tag/commit-count/dirty state), `GitDescription`, `TagComparator`, `StringUtil` (version pattern token expansion, `%t %b %B %c %C %h %H %f %F %S %D`).
5. `GitVersioningMojo` provides an explicit goal; the normal path needs no mojo execution.

`META-INF/maven/extension.xml` exports `org.emergent.gittle.maven` to the Maven core classloader — keep it in sync if packages move. Resolved values are also injected as `gittle.resolved.*` Maven properties.

Some test resources (`src/test/resources/testProjects/*`) still carry the upstream `maven-git-versioning-extension` naming (the project derives from qoomon's extension, hence `me/qoomon` in test sources).
