GroovyShell shell = new GroovyShell()
shell.setVariable('basedir', (File) binding.getVariable('basedir'))
def tools = shell.parse(new File((File) binding.getVariable('basedir'), '../tools/tools.groovy'))

static String s(Object o) { return String.valueOf(o) }

assert !tools.resolveFile('.git').exists()

tools.logPwd()
tools.gitInit('.', 'devel')
tools.gitUserInit()
tools.gitCommit("Empty commit [no ci]")
tools.gitTag('v1.0.0')
tools.gitCommit("Empty commit [no ci]")
