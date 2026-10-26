//file:noinspection unused

import org.w3c.dom.Element

import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathFactory
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.regex.Pattern

@groovy.transform.Field
boolean assertiveFileLoading = binding.hasVariable('assertive')
  ? Boolean.valueOf(String.valueOf(binding.getVariable('assertive')))
  : true

// https://maven.apache.org/plugins/maven-invoker-plugin/examples/pre-post-build-script.html
// basedir is a predefined global File for the root of the test project
if (!binding.hasVariable('basedir')) {
  throw new IllegalStateException("basedir was undefined!")
}

@groovy.transform.Field
File rootdir = ((File) binding.getVariable('basedir')).getAbsoluteFile()

//@groovy.transform.Field
//@groovy.transform.Final
//org.apache.maven.it.Verifier verifier = new org.apache.maven.it.Verifier(rootdir.getPath(), true);
//if (false) {
//  verifier.displayStreamBuffers();
//  verifier.executeGoal("verify")
//  verifier.verifyTextInLog("Building")
//  verifier.verifyErrorFreeLog()
//}

static String s(Object o) {
  return String.valueOf(o)
}

static String q(String s) {
  return Pattern.quote(s)
}

static ArrayList<String> exec(String[] env, File path, String execcmd, String[] subcmds) {
  Charset encoding = StandardCharsets.UTF_8;
  def outStream = new ByteArrayOutputStream()
  def errStream = new ByteArrayOutputStream()
  def proc = execcmd.execute(env, path)
  def inStream = proc.outputStream

  subcmds.each { cm ->
    inStream.write((cm + '\n').getBytes(encoding))
    inStream.flush()
  }

  inStream.write('exit\n'.getBytes(encoding))
  inStream.flush()
  proc.consumeProcessOutput(outStream, errStream)
  proc.waitFor()
  return [new String(outStream.toByteArray(), encoding), new String(errStream.toByteArray(), encoding)]
}

static void bash(File path, String[] subcmds) {
  assert path != null && path.exists()
  //SCRIPTDIR="$(dirname "${BASH_SOURCE[0]}")"
  def out = exec(null, path, "/usr/bin/env bash", subcmds)
  def stdOut = out[0]
  if (!stdOut.isBlank()) {
    print "OUT:\n" + stdOut
    if (!stdOut.endsWith('\n')) {
      println ''
    }
  }
  def errOut = out[1]
  if (!errOut.isBlank()) {
    print "ERR:\n" + errOut
    if (!errOut.endsWith('\n')) {
      println ''
    }
  }
}

void bash(String[] subcmds) {
  bash(rootdir, subcmds)
}

static String readFile(File path) {
  assert path != null && path.exists()
  return Files.readString(path.toPath(), StandardCharsets.UTF_8)
}

File getBasedir() {
  return rootdir
}

File resolveFile(String path) {
  return rootdir.toPath().resolve(path).toFile()
}

File getBuildLogBodyFile() {
  return resolveFile('build.log')
}

String getBuildLogBody() {
  File file = getBuildLogBodyFile()
  if (file != null && file.exists()) {
    return readFile(file)
  } else {
    if (assertiveFileLoading) {
      assert file != null
      assert file.exists()
    }
    return null
  }
}

def verifyTextInLog(String str) {
  return getBuildLogBody().contains(str)
}

def verifyTextInLog(GString gstr) {
  return verifyTextInLog(String.valueOf(gstr))
}

def verifyNoErrorsInLog() {
  return !verifyTextInLog("[ERROR]]")
}

static File getGittlePomFile(File file) {
  def path = file.toPath().toAbsolutePath()
  if ('pom.xml' == path.fileName.toString()) {
    path = path.getParent();
  }
  path = path.resolve('.gittle-pom.xml')
  return path.toFile()
}

File getGittlePomFile(String subpath) {
  def path = rootdir.toPath()
  if (!Set.of('', '.').contains(subpath)) {
    path = path.resolve(subpath)
  }
  return getGittlePomFile(path.toFile())
}

File resolveGittlePomFile() {
  return getGittlePomFile('')
}

private static Element parseXmlElement(InputStream is) {
  return DocumentBuilderFactory.newInstance()
    .newDocumentBuilder()
    .parse(is)
    .documentElement
}

private static String extractPomVersion(Element element) {
  def xpath = XPathFactory.newInstance().newXPath()
  String ver = xpath.evaluate('//project/version', element)
  if ('' == ver) {
    ver = xpath.evaluate('//project/parent/version', element)
  }
  if ('${revision}' == ver) {
    ver = xpath.evaluate('//project/properties/revision', element)
  }
  return ver
}

static String getGittlePomVersion(File file) {
  assert file != null && file.isFile()
  return extractPomVersion(parseXmlElement(new FileInputStream(file)))
}

//String getGittlePomVersion(String subpath) {
//  return getGittlePomVersion(getGittlePomFile(subpath))
//}
//
//String getGittlePomVersion() {
//  return getGittlePomVersion('')
//}

def gitInit(String path, String initialBranch) {
  bash([
    "git init --initial-branch \'$initialBranch\' \'$path\'",
  ] as String[])
}

def gitUserInit() {
  bash([
    "git config --local user.email \'github-actions[bot]\'",
    "git config --local user.name \'41898282+github-actions[bot]@users.noreply.github.com\'",
  ] as String[])
}

def gitInit() {
  gitInit('.', 'main')
  gitUserInit()
}

def gitTag(String name) {
  bash([
    "git tag \'$name\'",
  ] as String[])
}

def gitAnnotatedTag(String name, String message) {
  bash([
    "git tag -a -m \'$message\' \'$name\'",
  ] as String[])
}

def gitAnnotatedTag(String name) {
  gitAnnotatedTag(name, name)
}

def gitCommit(String message) {
  bash([
    "git commit --allow-empty -m \'$message\'",
  ] as String[])
}

def gitCommit(GString message) {
  return gitCommit(s(message))
}

def logPwd() {
  bash([
    "echo PWD=\${PWD}",
  ] as String[])
}


//void bash(String[] subcmds) {
//  bash(getBasedir(), subcmds)
//}

//def userProperties = context.get('userProperties')
//def server = new MockServer()
//userProperties.put('serverHost', server.getHost())
//userProperties.put('serverPort', server.getPort())

//File scriptDirFile = new File(getClass().protectionDomain.codeSource.location.path).getParentFile().getAbsoluteFile();
//def workPath = scriptDirFile.toPath()

//  //def loadScript(String file) {
//  //  Binding scriptBinding = new Binding()
//  //  scriptBinding.setVariable('basedir', binding.getVariable('basedir'))
//  //  def script = new GroovyShell(scriptBinding).parse(new File((File)binding.getVariable('basedir'), file))
//  //  script.metaClass.methods.each {
//  //    if (it.declaringClass.getTheClass() == script.class && !it.name.contains('$')
//  //      && it.name != 'main' && it.name != 'run') {
//  //      this.metaClass."$it.name" = script.&"$it.name"
//  //    }
//  //  }
//  //}
//  //loadScript('../tools/tools.groovy')
//
//  //@Field private CompilerConfiguration configuration
//  //configuration = new CompilerConfiguration()
//  //configuration.setScriptBaseClass('ToolsScript')
//  //GroovyShell shell = new GroovyShell(configuration)
//  //shell.setVariable('basedir', binding.getVariable('basedir'))
//  //shell.setVariable('ToolsScript', ToolsScript)
//  //tools = (ToolsScript)shell.parse(new File((File)binding.getVariable('basedir'),'../tools/tools.groovy'))
//  //tools.metaClass.methods.each {
//  //  if (it.declaringClass.getTheClass() == tools.class && Set.of('s').contains(it.name)) {
//  //    this.metaClass."$it.name" = tools.&"$it.name"
//  //  }
//  //}
//
//  //def tools = new GroovyScriptEngine( '..' ).with {
//  //  loadScriptByName('tools/tools.groovy')
//  //}
//  //this.metaClass.mixin tools
