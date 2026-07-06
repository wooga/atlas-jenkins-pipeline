package scripts

import tools.DeclarativeJenkinsSpec

class DotnetWrapperSpec extends DeclarativeJenkinsSpec {
    private static final String SCRIPT_PATH = "vars/dotnetWrapper.groovy"

    def setup() {
        helper.registerAllowedMethod("libraryResource", [String]) { String path -> "" }
        helper.registerAllowedMethod("powershell", [String]) { String script -> null }
        environment["HOME"] = "/home/tester"
        environment["LOCALAPPDATA"] = "C:\\Users\\tester\\AppData\\Local"
        environment["PATH"] = "/usr/bin"
    }

    private List<Object> shArgs() {
        return calls["sh"].collect { it.args[0] }
    }

    private List<Object> batArgs() {
        return calls["bat"].collect { it.args[0] }
    }

    def "string form provisions the SDK and runs the command via sh on unix"() {
        given:
        def dotnetWrapper = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox { dotnetWrapper "build --configuration Release" }

        then:
        shArgs().any { it instanceof String && it.contains("dotnet-install.sh") }
        shArgs().any { it instanceof Map && it.script == "dotnet build --configuration Release" }
    }

    def "map form with an explicit channel selector is provisioned and run"() {
        given:
        def dotnetWrapper = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox { dotnetWrapper(command: "test", channel: "8.0") }

        then:
        shArgs().any { it instanceof Map && it.script == "dotnet test" }
        usedEnvironments.find { it["DOTNET_CHANNEL"] == "8.0" } != null
    }

    def "map form with an explicit version selector is provisioned and run"() {
        given:
        def dotnetWrapper = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox { dotnetWrapper(command: "test", version: "8.0.401") }

        then:
        shArgs().any { it instanceof Map && it.script == "dotnet test" }
        usedEnvironments.find { it["DOTNET_VERSION"] == "8.0.401" } != null
    }

    def "conflicting selectors fail fast"() {
        given:
        def dotnetWrapper = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox { dotnetWrapper(command: "test", channel: "8.0", version: "8.0.401") }

        then:
        thrown(Exception)
    }

    def "returnStatus is threaded through to sh"() {
        given:
        def dotnetWrapper = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox { dotnetWrapper(command: "test", returnStatus: true) }

        then:
        def commandCall = shArgs().find { it instanceof Map && it.script == "dotnet test" }
        commandCall.returnStatus == true
    }

    def "returnStdout is threaded through to sh"() {
        given:
        def dotnetWrapper = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox { dotnetWrapper(command: "test", returnStdout: true) }

        then:
        def commandCall = shArgs().find { it instanceof Map && it.script == "dotnet test" }
        commandCall.returnStdout == true
    }

    def "runs via bat on Windows"() {
        given:
        helper.registerAllowedMethod("isUnix", []) { false }
        def dotnetWrapper = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox { dotnetWrapper "build" }

        then:
        batArgs().any { it instanceof Map && it.script == "dotnet build" }
        shArgs().isEmpty()
    }
}
