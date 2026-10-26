# gittle

Tools for generating and manipulating maven project versions from Git logs.

## Overview

This extension will set the project `revision` property based on the Git commit history.

The version format pattern is configurable and allows for using values such as the Git hash, branch name etc.

## Configuration

This is a maven build core extension that can -

- Participate in maven build lifecycle
- Automatically set the building project's version
- No explicit mojo executions needed to set the version
- Project's POM remain unchanged

To use as a maven build extension, create (or modify) `extensions.xml` file in `${project.baseDir}/.mvn/` to have:

```xml
<!-- .mvn/extensions.xml -->
<extensions xmlns="http://maven.apache.org/EXTENSIONS/1.0.0"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://maven.apache.org/EXTENSIONS/1.0.0
                                http://maven.apache.org/xsd/core-extensions-1.0.0.xsd">
    <extension>
        <groupId>org.emergent.gittle</groupId>
        <artifactId>gittle-maven-extension</artifactId>
        <version>${latest-version-here}</version>
    </extension>
</extensions>
```

Then change the pom version (or parent version) element values to '${revision}', and add a default value for the
`revision` property:

A single-module project example:
```xml
<!-- pom.xml -->
<project>

    <groupId>net.sample</groupId>
    <artifactId>app-1</artifactId>
    <version>${revision}</version>

    <properties>
        <revision>0.0.1-SNAPSHOT</revision>
    </properties>

    <!-- ... -->
</project>
```

A multi-module project example:
```xml
<!-- pom.xml
  Parent
-->
<project>

    <groupId>net.sample</groupId>
    <artifactId>sample-parent</artifactId>
    <version>${revision}</version>

    <modules>
        <module>lib-a</module>
    </modules>

    <properties>
        <revision>0.0.1-SNAPSHOT</revision>
    </properties>

    <!-- ... -->
</project>
```

```xml
<!-- lib-a/pom.xml
  Child
 -->
<project>

    <parent>
        <groupId>net.sample</groupId>
        <artifactId>sample-parent</artifactId>
        <version>${revision}</version>
    </parent>

    <artifactId>lib-a</artifactId>

    <!-- ... -->
</project>
```


See an example test project
at [project-with-extension](gittle-maven-extension/src/test/resources/project-with-extension/).

With just that configuration, next time your project runs any maven goals, you should see version from this module
is used by Maven reactor. Try running `mvn package` on your project.

## Version Pattern Customization

The default version pattern is `%t(-%B)(-%C)(-%S)(+%H)(.%D)`, but this can be customized by setting the
`gittle.versionPattern` property in the `.mvn/gittle-maven-extension.properties` file.

The following example will generate versions as `major.minor.patch+shorthash`, eg. `1.2.3+a5a29f8`.

Example configuration for version pattern
```properties
# Will generate something like 1.2.3+a5a29f8
gittle.versionPattern=%t+%h
```

Available Tokens for Version Pattern

Note: Any token may be wrapped within parenthesis, along with other literal characters.  If the token evaluates to
empty for strings or zero (0) for numbers then the entire group is omitted, for any other value the entire group is
kept excluding the surrounding parenthesis.

| Token | Description                                                          |
|-------|----------------------------------------------------------------------|
| %t    | Most recent tag name (by default dropping any 'v' prefix)            |
| %b    | Branch name                                                          |
| %B    | Branch name (empty when a release branch, by default main or master) |
| %c    | Commit count (since most recent tag)                                 |
| %C    | Commit count (empty when commit count would be zero)                 |
| %h    | Short hash ref                                                       |
| %H    | Short hash ref (empty when release branch and commit count is zero)  |
| %f    | Full hash ref                                                        |
| %F    | Full hash ref (empty when release branch and commit count is zero)   |
| %S    | 'SNAPSHOT' (empty when commit count is zero)                         |
| %D    | 'dirty' (empty when no uncommited changes exist in workspace)        |

## Version Pattern Examples

| Pattern           | %t = 1.2.3<br/>%b = main<br/>%c = 0 | %b = devel  | %c = 5           | %b = devel<br/>%c = 5  |
|-------------------|-------------------------------------|-------------|------------------|------------------------|
| %t(-%B)(-%C)(-%S) | 1.2.3                               | 1.2.3-devel | 1.2.3-5-SNAPSHOT | 1.2.3-devel-5-SNAPSHOT |


## Generated Version Access

This extension adds all the resolved properties to *Maven properties* during build cycle.

```properties
# example injected maven properties
gittle.resolved.branch=main
gittle.resolved.hash=67550ad6a64fe4e09bf9e36891c09b2f7bdc52f9
gittle.resolved.hashShort=67550ad
gittle.resolved.tagVersion=0.0.1
gittle.resolved.commits=0
gittle.resolved.dirty=false
gittle.resolved.version=0.0.1
```

You may use these properties in maven pom file, for example as `${gittle.resolved.branch}` to access git branch name.
