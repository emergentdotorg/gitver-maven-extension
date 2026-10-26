GroovyShell shell = new GroovyShell()
shell.setVariable('basedir', (File) binding.getVariable('basedir'))
def tools = shell.parse(new File((File) binding.getVariable('basedir'), '../tools/tools.groovy'))

static String s(Object o) { return String.valueOf(o) }

def projectName = "devel-branch-test"
def expectedVersionPrefix = '1.0.0-devel-1-SNAPSHOT+'

assert tools.verifyNoErrorsInLog()
def dotGitDir = tools.resolveFile('.git')
assert dotGitDir != null || dotGitDir.exists()
File gittlePom = tools.resolveGittlePomFile()
assert gittlePom != null && gittlePom.exists()
String version = tools.getGittlePomVersion(gittlePom)
// should be a hash code attached to the actual version
assert version != expectedVersionPrefix
// but we should have the prefix right
assert version.startsWith(expectedVersionPrefix)
assert tools.verifyTextInLog("Building $projectName $version")
