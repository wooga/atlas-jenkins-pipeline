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
        credentials.addUsernamePassword("artifactory_read", "fake-jfrog-user", "fake-jfrog-pass")
    }

    private List<Object> shArgs() {
        return calls["sh"].collect { it.args[0] }
    }

    private List<Object> batArgs() {
        return calls["bat"].collect { it.args[0] }
    }

    private String installShString() {
        return shArgs().find { it instanceof String && it.contains("dotnet-install.sh") }
    }

    def "string form installs the SDK and runs the command via sh on unix"() {
        given:
        def dotnetWrapper = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox { dotnetWrapper "build --configuration Release" }

        then:
        installShString() != null
        shArgs().any { it instanceof Map && it.script == "dotnet build --configuration Release" }
    }

    def "map form with an explicit channel selector is installed and run"() {
        given:
        def dotnetWrapper = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox { dotnetWrapper(command: "test", channel: "8.0") }

        then:
        shArgs().any { it instanceof Map && it.script == "dotnet test" }
        installShString().contains("--channel '8.0'")
    }

    def "map form with an explicit version selector is installed and run"() {
        given:
        def dotnetWrapper = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox { dotnetWrapper(command: "test", version: "8.0.401") }

        then:
        shArgs().any { it instanceof Map && it.script == "dotnet test" }
        installShString().contains("--version '8.0.401'")
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

    def "default NuGet source and credentials are applied when not overridden"() {
        given:
        def dotnetWrapper = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox { dotnetWrapper(command: "test") }

        then:
        def nugetCall = shArgs().find { it instanceof String && it.contains("nuget add source") }
        nugetCall != null
        nugetCall.contains("wooga_nuget")
        usedEnvironments.find { it["NuGetPackageSourceCredentials_wooga_nuget"] == "Username=fake-jfrog-user;Password=fake-jfrog-pass" } != null
    }

    def "NuGet source and credentials can be overridden"() {
        given:
        credentials.addUsernamePassword("custom_creds", "custom-user", "custom-pass")
        def dotnetWrapper = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox {
            dotnetWrapper(command: "test", nugetSourceName: "custom_source", nugetSourceUrl: "https://example.com/index.json", nugetCredentialsId: "custom_creds")
        }

        then:
        def nugetCall = shArgs().find { it instanceof String && it.contains("nuget add source") }
        nugetCall != null
        nugetCall.contains("custom_source")
        nugetCall.contains("https://example.com/index.json")
        usedEnvironments.find { it["NuGetPackageSourceCredentials_custom_source"] == "Username=custom-user;Password=custom-pass" } != null
    }

    def "nuget: false fully opts out of NuGet setup"() {
        given:
        def dotnetWrapper = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox { dotnetWrapper(command: "test", nuget: false) }

        then:
        shArgs().every { !(it instanceof String && it.contains("nuget add source")) }
        calls["withCredentials"].size() == 0
    }
}
