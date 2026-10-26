GroovyShell shell = new GroovyShell()
shell.setVariable('basedir', (File) binding.getVariable('basedir'))
def tools = shell.parse(new File((File) binding.getVariable('basedir'), '../tools/tools.groovy'))

static String s(Object o) { return String.valueOf(o) }

String expectedVersion = "1.0.0"

assert tools.verifyNoErrorsInLog()
def dotGitDir = tools.resolveFile('.git')
assert dotGitDir != null && dotGitDir.isDirectory()
File gittlePom = tools.resolveGittlePomFile()
assert gittlePom != null && gittlePom.isFile()
String version = tools.getGittlePomVersion(gittlePom)
assert expectedVersion == version
String gittlePomBody = tools.readFile(gittlePom)
assert gittlePomBody.contains(s("<version>$version</version>"))
assert tools.verifyTextInLog("Building multi-module-parent $version")
