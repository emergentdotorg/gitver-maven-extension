import java.time.Instant

def baseDir = new File("$basedir")
def log = new PrintWriter((Writer) new File(baseDir, "setup.log").newWriter("UTF-8"), true)
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

log.printf("%s%n", "Setup started at: ${Instant.now()} in $baseDir")

[
  "git --version",
  "rm -rf .git",
  "git init --initial-branch main",
  "git config user.name nobody",
  "git config user.email nobody@nowhere.com",
  "git config advice.addIgnoredFile false",
  "git add .",
  "git commit --message=initial_commit",
  "git tag v0.9.0",
  "git commit --allow-empty --message=empty_commit",
  "git tag v1.0.0",
  "git checkout v0.9.0",
  "git log --graph --oneline"
].each executor

log.printf("%s%n", "Setup completed at ${Instant.now()}")
log.close()
return true
