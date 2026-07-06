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
        blockEnv["DOTNET_ROOT"] == "/home/tester/.cache/dotnet"
        blockEnv["PATH"].startsWith("/home/tester/.cache/dotnet")
        blockEnv["NuGetPackageSourceCredentials_wooga_nuget"] == "Username=fake-jfrog-user;Password=fake-jfrog-pass"
    }

    def "provisions with an explicit selector before running the block"() {
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
        usedEnvironments.find { it["DOTNET_VERSION"] == "8.0.401" } != null
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

    def "provisions via powershell on Windows"() {
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
        blockEnv["DOTNET_ROOT"] == "C:\\Users\\tester\\AppData\\Local\\cache\\dotnet"
    }
}
