package integration

import org.codehaus.groovy.control.CompilerConfiguration
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.GroovySandbox
import org.junit.Rule
import tools.DeclarativeJenkinsSpec
import org.jvnet.hudson.test.JenkinsRule


class IntegrationSpec extends DeclarativeJenkinsSpec {

    private static final String SCRIPT_PATH = "vars/buildGradlePlugin.groovy"

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    protected Object runsInJenkinsSandbox(String scriptName) {
        CompilerConfiguration cc = GroovySandbox.createSecureCompilerConfiguration();
        def sh = new GroovyShell(DeclarativeJenkinsSpec.classLoader, binding, cc);
        return new GroovySandbox().runScript(sh, new File(scriptName).text)
    }

    def "test"() {
        given:
        when:
        def script = runsInJenkinsSandbox(SCRIPT_PATH)
        then:
        false

    }
}
