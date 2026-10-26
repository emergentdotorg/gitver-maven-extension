GroovyShell shell = new GroovyShell()
shell.setVariable('basedir', (File) binding.getVariable('basedir'))
def tools = shell.parse(new File((File) binding.getVariable('basedir'), '../tools/tools.groovy'))

static String s(Object o) { return String.valueOf(o) }

assert !tools.resolveFile('.git').exists()

def keyword = "[major]"

tools.logPwd()
tools.gitInit()
tools.gitCommit(s("chore(release): $keyword"))
tools.gitTag('v1.0.0')

