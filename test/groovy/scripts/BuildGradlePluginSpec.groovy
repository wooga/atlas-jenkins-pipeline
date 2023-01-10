package scripts

import com.lesfurets.jenkins.unit.MethodCall
import spock.lang.Unroll
import tools.DeclarativeJenkinsSpec
import tools.GradleInvocation

class BuildGradlePluginSpec extends DeclarativeJenkinsSpec {
    private static final String SCRIPT_PATH = "vars/buildGradlePlugin.groovy"

    def setup() {
        binding.setVariable("params", [RELEASE_TYPE: "snapshot"])
    }

    def "posts coveralls results to coveralls server"() {
        given: "loaded buildGradlePlugin in a successful build"
        def buildGradlePlugin = loadSandboxedScript(SCRIPT_PATH) {
            currentBuild["result"] = "SUCCESS"
        }

        and: "a coveralls token"
        def coverallsToken = "token"

        when: "running gradle pipeline with coverallsToken parameter"
        inSandbox { buildGradlePlugin(coverallsToken: coverallsToken) }

        then: "request is sent to coveralls webhook"
        calls.has["httpRequest"] { MethodCall call ->
            Map params = call.args[0] as Map
            return params["httpMode"] == "POST" &&
                    params["ignoreSslErrors"] == true &&
                    params["url"] == "https://coveralls.io/webhook?repo_token=${coverallsToken}"
        }
    }

    @Unroll("publishes #releaseType-#releaseScope release ")
    def "publishes with #release release type"() {
        given: "credentials holder with publish keys"
        credentials.addString('gradle.publish.key', "key")
        credentials.addString('gradle.publish.secret', "secret")
        and: "build plugin with publish parameters"
        def buildGradlePlugin = loadSandboxedScript(SCRIPT_PATH) {
            currentBuild["result"] = null
            params.RELEASE_TYPE = releaseType
            params.RELEASE_SCOPE = releaseScope
        }

        when: "running buildGradlePlugin pipeline"
        inSandbox { buildGradlePlugin() }

        then: "runs gradle with parameters"
        def gradleCall = getShGradleCalls().first()
        skipsRelease || (gradleCall != null)
        def publishCall = GradleInvocation.parseCommandLine(gradleCall)

        skipsRelease ^ publishCall.tasks.contains(releaseType?: "snapshot")
        skipsRelease ^ publishCall["gradle.publish.key"] == "key"
        skipsRelease ^ publishCall["gradle.publish.secret"] == "secret"
        skipsRelease ^ publishCall["release.stage"] == (releaseType?: "snapshot")
        skipsRelease ^ publishCall["release.scope"] == (releaseScope?: "")
        skipsRelease ^ publishCall.containsRaw("-x check")

        where:
        releaseType | releaseScope | skipsRelease
        null        | null         | true
        "snapshot"  | "patch"      | true
        "rc"        | "minor"      | false
        "final"     | "major"      | false
    }

    def "registers environment on publish"() {
        given: "set needed credentials"
        credentials.addUsernamePassword('github_access', "usr", "pwd")
        and: "build plugin with publish parameters"
        def buildGradlePlugin = loadSandboxedScript(SCRIPT_PATH) {
            params.RELEASE_TYPE = "not-snapshot"
            params.RELEASE_SCOPE = "any"
            env.GRGIT_USR = "usr"
            env.GRGIT_PSW = "pwd"
        }

        when: "running buildGradlePlugin pipeline"
        inSandbox { buildGradlePlugin() }

        then: "sets up GRGIT environment"
        def env = buildGradlePlugin.binding.env
        env["GRGIT"] == credentials['github_access']
        env["GRGIT_USER"] == "usr" //"${GRGIT_USR}"
        env["GRGIT_PASS"] == "pwd" //"${GRGIT_PSW}"
        and: "sets up github environment"
        env["GITHUB_LOGIN"] == "usr" //"${GRGIT_USR}"
        env["GITHUB_PASSWORD"] == "pwd" //"${GRGIT_PSW}"
    }


    @Unroll("#description publish workspace when clearWs is #clearWs")
    def "clears publish and preparation workspaces if clearWs is set"() {
        given: "loaded check in a running jenkins build"
        def buildGradlePlugin = loadSandboxedScript(SCRIPT_PATH) {
            params.RELEASE_TYPE = "not-snapshot"
            params.RELEASE_SCOPE = "any"
        }

        when: "running pipeline"
        inSandbox {
            buildGradlePlugin(platforms: ["linux"], clearWs: clearWs)
        }

        then: "all workspaces are clean"
        calls["cleanWs"].length == (clearWs ? 2 : 0) //publish (1) + preparation (1)

        where:
        clearWs << [true, false]
        description = clearWs ? "clears" : "doesn't clear"
    }

    String[] getShGradleCalls() {
        return calls["sh"].collect { it.args[0]["script"].toString() }.findAll {
            it.contains("gradlew")
        }
    }
}
