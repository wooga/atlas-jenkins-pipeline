package scripts

import tools.DeclarativeJenkinsSpec

class RunDotnetToolSpec extends DeclarativeJenkinsSpec {
    private static final String SCRIPT_PATH = "vars/runDotnetTool.groovy"

    def setup() {
        helper.registerAllowedMethod("libraryResource", [String]) { String path -> "" }
        helper.registerAllowedMethod("powershell", [String]) { String script -> null }
        environment["HOME"] = "/home/tester"
        environment["LOCALAPPDATA"] = "C:\\Users\\tester\\AppData\\Local"
        environment["PATH"] = "/usr/bin"
        credentials.addUsernamePassword("artifactory_read", "fake-jfrog-user", "fake-jfrog-pass")
    }

    private List<Object> shArgs() {
        return calls["sh"].collect { it.args[0] }
    }

    private List<Object> batArgs() {
        return calls["bat"].collect { it.args[0] }
    }

    def "simple form installs the tool then runs it via dotnet tool run"() {
        given:
        def runDotnetTool = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox { runDotnetTool("MyTool", "mytool", ["--help", "--verbose"]) }

        then:
        shArgs().any { it instanceof String && it.contains("dotnet tool install MyTool") && it.contains("--create-manifest-if-needed") }
        shArgs().any { it instanceof Map && it.script == "dotnet tool run mytool --help --verbose" }
    }

    def "map form with an explicit version passes --allow-downgrade"() {
        given:
        def runDotnetTool = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox { runDotnetTool(packageId: "MyTool", toolBinary: "mytool", args: ["run"], version: "1.2.3") }

        then:
        shArgs().any { it instanceof String && it.contains("--version 1.2.3") && it.contains("--allow-downgrade") }
        shArgs().any { it instanceof Map && it.script == "dotnet tool run mytool run" }
    }

    def "map form threads returnStatus through"() {
        given:
        def runDotnetTool = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox { runDotnetTool(packageId: "MyTool", toolBinary: "mytool", args: [], returnStatus: true) }

        then:
        def runCall = shArgs().find { it instanceof Map && it.script == "dotnet tool run mytool" }
        runCall.returnStatus == true
    }

    def "runs via bat on Windows"() {
        given:
        helper.registerAllowedMethod("isUnix", []) { false }
        def runDotnetTool = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox { runDotnetTool("MyTool", "mytool", ["--help"]) }

        then:
        batArgs().any { it instanceof String && it.contains("dotnet tool install MyTool") }
        batArgs().any { it instanceof Map && it.script == "dotnet tool run mytool --help" }
        shArgs().isEmpty()
    }
}
