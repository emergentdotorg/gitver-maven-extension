import java.time.Instant

String originalVer = '\\$\\{revision\\}'
String expectedVer = "1.0.0"

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

//foundLines = buildLog.findAll { it =~ /$groupId:main:$originalVer -> $expectedVer/ }
//assert 1 == foundLines.size()
//
//File repoPom = new File(repoDir, "${groupPath}/main/${expectedVer}/main-${expectedVer}.pom")
//assert repoPom.isFile()
//assert 1 == repoPom.readLines().findAll {it =~ /<version>${expectedVer}<\/version>/}.size()

foundLines = buildLog.findAll { it =~ /$groupId:api:$originalVer -> $expectedVer/ }
assert 1 == foundLines.size()

File apiRepoPom = new File(repoDir, "${groupPath}/api/${expectedVer}/api-${expectedVer}.pom")
assert apiRepoPom.isFile()
assert 1 == apiRepoPom.readLines().findAll {it =~ /<version>${expectedVer}<\/version>/}.size()

log.printf("%s%n", "Verify completed at ${Instant.now()}")
log.close()
return true
