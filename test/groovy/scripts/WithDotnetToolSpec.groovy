package scripts

import tools.DeclarativeJenkinsSpec

class WithDotnetToolSpec extends DeclarativeJenkinsSpec {
    private static final String SCRIPT_PATH = "vars/withDotnetTool.groovy"

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

    def "installs the tool then runs the block"() {
        given:
        def withDotnetTool = loadSandboxedScript(SCRIPT_PATH)
        def ran = false

        when:
        inSandbox {
            withDotnetTool("MyTool") {
                ran = true
            }
        }

        then:
        ran
        shArgs().any { it instanceof String && it.contains("dotnet tool install MyTool") && it.contains("--create-manifest-if-needed") }
    }

    def "passes version and --allow-downgrade when a version is given"() {
        given:
        def withDotnetTool = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox {
            withDotnetTool(packageId: "MyTool", version: "1.2.3") { }
        }

        then:
        shArgs().any { it instanceof String && it.contains("--version 1.2.3") && it.contains("--allow-downgrade") }
    }

    def "redirects the tool cache under the shared cache dir"() {
        given:
        def withDotnetTool = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox {
            withDotnetTool("MyTool") { }
        }

        then:
        usedEnvironments.find { it["NUGET_PACKAGES"] == "/home/tester/.cache/jenkins-pipeline/dotnet/tools/packages" } != null
        usedEnvironments.find { it["DOTNET_CLI_HOME"] == "/home/tester/.cache/jenkins-pipeline/dotnet/tools" } != null
    }

    def "installs via bat on Windows"() {
        given:
        helper.registerAllowedMethod("isUnix", []) { false }
        def withDotnetTool = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox {
            withDotnetTool("MyTool") { }
        }

        then:
        calls["bat"].collect { it.args[0] }.any { it instanceof String && it.contains("dotnet tool install MyTool") }
        shArgs().every { !(it instanceof String && it.contains("dotnet tool install")) }
    }
}
