import java.time.Instant

def baseDir = new File("$basedir")
def repoDir = new File("$localRepositoryPath")
def log = new PrintWriter((Writer)new File(baseDir, "verify.log").newWriter("UTF-8"), true)
def executor = {command ->
  def proc = command.execute(null, basedir)
  def sout = new StringBuilder(), serr = new StringBuilder()
  proc.waitForProcessOutput(sout, serr)
  log.printf("%s%n", "cmd: $command")
  log.printf("%s%n", "out: ${sout.toString().trim()}")
  log.printf("%s%n", "err: ${serr.toString().trim()}")
  log.printf("%s%n", "ret: ${proc.exitValue()}")
  assert proc.exitValue() == 0
}

log.printf("%s%n", "Verify started at ${Instant.now()} in: $basedir")

[
  //  "chmod -R 755 .git",
  //  "rm -rf .git"
].each executor

String groupId = "test.standard-project"
String originalVersion = "0.0.1-SNAPSHOT"
String expectedVersion = "1.0.2-SNAPSHOT"
String groupPath = groupId.replace('.', '/')

def buildLogFile = new File(baseDir, "build.log")
assert buildLogFile.isFile()
def buildLog = buildLogFile.readLines()

def foundLines = buildLog

// Check the main version was used by the plugin execution
foundLines = buildLog.findAll { it =~ /$groupId:main:$originalVersion -> $expectedVersion/ }
assert 1 == foundLines.size()

// And check that the produced artifact was installed with the good version
File installedPomFile = new File(repoDir, "${groupPath}/main/${expectedVersion}/main-${expectedVersion}.pom")
assert installedPomFile.isFile()
assert 1 == installedPomFile.readLines().findAll {it =~ /<version>${expectedVersion}<\/version>/}.size()

log.printf("%s%n", "Verify completed at ${Instant.now()}")
log.close()
return true
