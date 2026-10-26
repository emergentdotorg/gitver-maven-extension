GroovyShell shell = new GroovyShell()
shell.setVariable('basedir', (File) binding.getVariable('basedir'))
def tools = shell.parse(new File((File) binding.getVariable('basedir'), '../tools/tools.groovy'))

static String s(Object o) { return String.valueOf(o) }

String expectedVersion = "1.0.0"

assert tools.verifyNoErrorsInLog()
def dotGitDir = tools.resolveFile('.git')
assert dotGitDir != null || dotGitDir.exists()
File gittlePom = tools.resolveGittlePomFile()
assert gittlePom != null && gittlePom.exists()
String version = tools.getGittlePomVersion(gittlePom)
assert expectedVersion == version
String gittlePomBody = tools.readFile(gittlePom)
assert gittlePomBody != null
assert gittlePomBody.contains(s("<version>$version</version>"))
assert tools.verifyTextInLog("Building parent-test-pom 1.0.0")
assert tools.verifyTextInLog("Building parent-test-pom $version");
assert tools.verifyTextInLog("Building cli $version");
assert tools.verifyTextInLog("Setting parent org.emergent.its.gittle:parent-test-pom:pom:\${revision} version to $version");
assert tools.verifyTextInLog("Building lib $version");
