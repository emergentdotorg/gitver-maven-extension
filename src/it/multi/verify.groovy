import java.time.Instant

String originalVer = "0.0.1-SNAPSHOT"
String expectedVer = "1.2.3"

def baseDir = new File("$basedir")
def repoDir = new File("$localRepositoryPath")
def log = new PrintWriter((Writer)new File(baseDir, "verify.log").newWriter("UTF-8"), true)
log.printf("%s%n", "Verify started at ${Instant.now()} in: $basedir")

String groupId = "test.${baseDir.getName()}"
String groupPath = groupId.replace('.', '/')

def buildLogFile = new File(baseDir, "build.log")
assert buildLogFile.isFile()
def buildLog = buildLogFile.readLines()

def foundLines = buildLog

// Check the main version was used by the plugin execution
foundLines = buildLog.findAll { it =~ /$groupId:main:$originalVer -> $expectedVer/ }
assert 1 == foundLines.size()

// Check the child version was used by the plugin execution
foundLines = buildLog.findAll { it =~ /$groupId:api:$originalVer -> $expectedVer/ }
assert 1 == foundLines.size()

// Check the child with ext parent version was used by the plugin execution
foundLines = buildLog.findAll { it =~ /$groupId:logic:$originalVer -> $expectedVer/ }
assert 1 == foundLines.size()

// Check the child with ext parent version was used by the plugin execution
foundLines = buildLog.findAll { it =~ /$groupId:nested:$originalVer -> $expectedVer/ }
assert 1 == foundLines.size()

// Check the child with ext parent version was used by the plugin execution
foundLines = buildLog.findAll { it =~ /$groupId:nested-logic:$originalVer -> $expectedVer/ }
assert 1 == foundLines.size()

// And check that the produced artifact was installed with the good version
File repoPom = new File(repoDir, "${groupPath}/main/${expectedVer}/main-${expectedVer}.pom")
assert repoPom.isFile()
assert 1 == repoPom.readLines().findAll {it =~ /<version>${expectedVer}<\/version>/}.size()

File apiRepoPom = new File(repoDir, "${groupPath}/api/${expectedVer}/api-${expectedVer}.pom")
assert apiRepoPom.isFile()
assert 1 == apiRepoPom.readLines().findAll {it =~ /<version>${expectedVer}<\/version>/}.size()

File logicRepoPom = new File(repoDir, "${groupPath}/logic/${expectedVer}/logic-${expectedVer}.pom")
assert logicRepoPom.isFile()
assert 1 == logicRepoPom.readLines().findAll {it =~ /<version>${expectedVer}<\/version>/}.size()

File nestedRepoPom = new File(repoDir, "${groupPath}/nested/${expectedVer}/nested-${expectedVer}.pom")
assert nestedRepoPom.isFile()
assert 1 == nestedRepoPom.readLines().findAll {it =~ /<version>${expectedVer}<\/version>/}.size()

File nestedLogicRepoPom = new File(repoDir, "${groupPath}/nested-logic/${expectedVer}/nested-logic-${expectedVer}.pom")
assert nestedLogicRepoPom.isFile()
assert 1 == nestedLogicRepoPom.readLines().findAll {it =~ /<version>${expectedVer}<\/version>/}.size()

log.printf("%s%n", "Verify completed at ${Instant.now()}")
log.close()
return true
