import java.time.Instant

String originalVer = "0.0.1-SNAPSHOT"
String expectedVer = "0.9.0"

def baseDir = new File("$basedir")
def repoDir = new File("$localRepositoryPath")
def log = new PrintWriter((Writer)new File(baseDir, "verify.log").newWriter("UTF-8"), true)
log.printf("%s%n", "Verify started at ${Instant.now()} in: $basedir")

String groupId = "test.${baseDir.getName()}"
String groupPath = groupId.replace('.', '/')

def buildLogFile = new File(baseDir, "build.log")
assert buildLogFile.isFile()
def buildLog = buildLogFile.readLines()

// Check the main version was used by the plugin execution
def foundLines = buildLog.findAll { it =~ /$groupId:main:$originalVer -> $expectedVer/ }
assert 1 == foundLines.size()

// And check that the produced artifact was installed with the good version
File repoPom = new File(repoDir, "${groupPath}/main/${expectedVer}/main-${expectedVer}.pom")
assert repoPom.isFile()
assert 1 == repoPom.readLines().findAll {it =~ /<version>${expectedVer}<\/version>/}.size()

log.printf("%s%n", "Verify completed at ${Instant.now()}")
log.close()
return true
