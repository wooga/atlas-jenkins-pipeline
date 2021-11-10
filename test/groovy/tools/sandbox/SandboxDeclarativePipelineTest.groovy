package tools.sandbox

import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import org.jenkinsci.plugins.scriptsecurity.sandbox.Whitelist

class SandboxDeclarativePipelineTest extends DeclarativePipelineTest {

    SandboxDeclarativePipelineTest(Whitelist whitelist) {
        super()
        this.helper = new SandboxPipelineTestHelper(whitelist)
    }
}
