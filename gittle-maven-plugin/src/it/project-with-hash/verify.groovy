//file:noinspection GrUnresolvedAccess
GroovyShell shell = new GroovyShell()
shell.setVariable('basedir', (File) binding.getVariable('basedir'))
def tools = shell.parse(new File((File) binding.getVariable('basedir'), '../tools/tools.groovy'))

static String s(Object o) { return String.valueOf(o) }

def verifier = new org.apache.maven.it.Verifier(tools.rootdir.getPath(), true)
if (false) {
  verifier.displayStreamBuffers();
  verifier.executeGoal("verify")
  verifier.verifyTextInLog("Building")
  verifier.verifyErrorFreeLog()
}

def expectedPrefix = '1.0.0+'

assert tools.verifyNoErrorsInLog()
def dotGitDir = tools.resolveFile('.git')
assert dotGitDir != null || dotGitDir.exists()
File gittlePom = tools.resolveGittlePomFile()
assert gittlePom != null && gittlePom.exists()
def version = tools.getGittlePomVersion(gittlePom)
assert version.startsWith(expectedPrefix)
assert tools.verifyTextInLog("Building project-with-hash $version")
