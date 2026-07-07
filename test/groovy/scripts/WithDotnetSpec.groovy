package scripts

import tools.DeclarativeJenkinsSpec

class WithDotnetSpec extends DeclarativeJenkinsSpec {
    private static final String SCRIPT_PATH = "vars/withDotnet.groovy"

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

    def "runs the block with the cache dir on PATH and DOTNET_ROOT set"() {
        given:
        def withDotnet = loadSandboxedScript(SCRIPT_PATH)
        def ran = false

        when:
        inSandbox {
            withDotnet {
                ran = true
            }
        }

        then:
        ran
        def blockEnv = usedEnvironments.find { it.containsKey("DOTNET_ROOT") }
        blockEnv != null
        blockEnv["DOTNET_ROOT"] == "/home/tester/.cache/jenkins-pipeline/dotnet"
        blockEnv["PATH"].startsWith("/home/tester/.cache/jenkins-pipeline/dotnet")
        blockEnv["NuGetPackageSourceCredentials_wooga_nuget"] == "Username=fake-jfrog-user;Password=fake-jfrog-pass"
        // NUGET_PACKAGES/DOTNET_CLI_HOME are always redirected under the shared
        // cache tree now, even for plain withDotnet (not just withDotnetTool),
        // so nothing this library does with `dotnet` can ever write to the
        // user's default ~/.nuget or ~/.dotnet locations.
        blockEnv["NUGET_PACKAGES"] == "/home/tester/.cache/jenkins-pipeline/dotnet/tools/packages"
        blockEnv["DOTNET_CLI_HOME"] == "/home/tester/.cache/jenkins-pipeline/dotnet/tools"
    }

    def "installs with an explicit selector before running the block"() {
        given:
        def withDotnet = loadSandboxedScript(SCRIPT_PATH)
        def ran = false

        when:
        inSandbox {
            withDotnet(version: "8.0.401") {
                ran = true
            }
        }

        then:
        ran
        shArgs().find { it instanceof String && it.contains("dotnet-install.sh") }.contains("--version '8.0.401'")
    }

    def "conflicting selectors fail fast"() {
        given:
        def withDotnet = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox {
            withDotnet(version: "8.0.401", channel: "8.0") {
                throw new IllegalStateException("block should not run")
            }
        }

        then:
        thrown(Exception)
    }

    def "installs via powershell on Windows"() {
        given:
        helper.registerAllowedMethod("isUnix", []) { false }
        def withDotnet = loadSandboxedScript(SCRIPT_PATH)
        def ran = false

        when:
        inSandbox {
            withDotnet {
                ran = true
            }
        }

        then:
        ran
        // one powershell call for the install wrapper, one for the idempotent nuget source registration
        calls["powershell"].size() == 2
        calls["sh"].size() == 0
        def blockEnv = usedEnvironments.find { it.containsKey("DOTNET_ROOT") }
        blockEnv != null
        blockEnv["DOTNET_ROOT"] == "C:\\Users\\tester\\AppData\\Local\\cache\\jenkins-pipeline\\dotnet"
    }

    def "NuGet source and credentials can be overridden"() {
        given:
        credentials.addUsernamePassword("custom_creds", "custom-user", "custom-pass")
        def withDotnet = loadSandboxedScript(SCRIPT_PATH)
        def ran = false

        when:
        inSandbox {
            withDotnet(nugetSourceName: "custom_source", nugetSourceUrl: "https://example.com/index.json", nugetCredentialsId: "custom_creds") {
                ran = true
            }
        }

        then:
        ran
        def nugetCall = shArgs().find { it instanceof String && it.contains("nuget add source") }
        nugetCall != null
        nugetCall.contains("custom_source")
        usedEnvironments.find { it["NuGetPackageSourceCredentials_custom_source"] == "Username=custom-user;Password=custom-pass" } != null
    }

    def "nuget: false fully opts out of NuGet setup"() {
        given:
        def withDotnet = loadSandboxedScript(SCRIPT_PATH)
        def ran = false

        when:
        inSandbox {
            withDotnet(nuget: false) {
                ran = true
            }
        }

        then:
        ran
        shArgs().every { !(it instanceof String && it.contains("nuget add source")) }
        calls["withCredentials"].size() == 0
    }
}
