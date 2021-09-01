package specs

import com.lesfurets.jenkins.unit.MethodCall
import spock.lang.Unroll
import tools.DeclarativeJenkinsSpec

class BuildGradlePluginSpec extends DeclarativeJenkinsSpec {
    private static final String SCRIPT_PATH = "vars/newBuildGradlePlugin.groovy"

    def setupSpec() {
        binding.setVariable("params", [RELEASE_TYPE: "snapshot"])
        binding.setVariable("BRANCH_NAME", "any")
        helper.registerAllowedMethod("isUnix") { true }
    }

    def "posts coveralls results to coveralls server" () {
        given: "loaded buildGradlePlugin in a successful build"
        helper.registerAllowedMethod("httpRequest", [LinkedHashMap]) {}
        def buildGradlePlugin = loadScript(SCRIPT_PATH) {
            currentBuild["result"] = "SUCCESS"
        }

        and: "a coveralls token"
        def coverallsToken = "token"

        when: "running gradle pipeline with coverallsToken parameter"
        buildGradlePlugin(coverallsToken: coverallsToken)

        then: "request is sent to coveralls webhook"
        hasMethodCallWith("httpRequest"){ MethodCall call ->
            Map params = call.args[0] as Map
            return params["httpMode"] == "POST" &&
                    params["ignoreSslErrors"] == true &&
                    params["url"] == "https://coveralls.io/webhook?repo_token=${coverallsToken}"
        }
    }

    @Unroll("publishes #releaseType-#releaseScope release ")
    def "publishes with #release release type"() {
        given: "build plugin with publish parameters"
        def buildGradlePlugin = loadScript(SCRIPT_PATH) {
            currentBuild["result"] = null
            params.RELEASE_TYPE = releaseType
            params.RELEASE_SCOPE = releaseScope
        }
        helper.registerAllowedMethod("credentials", [String]) {it -> it}

        when: "running buildGradlePlugin pipeline"
        buildGradlePlugin()

        then: "runs gradle with parameters"
        skipsRelease ^/*XOR*/ hasShCallWith { it ->
            it.contains("gradlew") &&
            it.contains(releaseType) &&
            it.contains("-Pgradle.publish.key=gradle.publish.key") &&
            it.contains("-Pgradle.publish.secret=gradle.publish.secret") &&
            it.contains("-Prelease.stage=${releaseType}") &&
            it.contains("-Prelease.scope=${releaseScope}")
            it.contains("-x check")
        }

        where:
        releaseType | releaseScope | skipsRelease
        "snapshot"  | "patch"      | true
        "rc"        | "minor"      | false
        "final"     | "major"      | false
    }

    def "registers environment on publish"() {
        given: "build plugin with publish parameters"
        def buildGradlePlugin = loadScript(SCRIPT_PATH) {
            currentBuild["result"] = null
            params.RELEASE_TYPE = "not-snapshot"
            params.RELEASE_SCOPE = "any"
            env.GRGIT_USR = "usr"
            env.GRGIT_PSW = "pwd"
        }
        helper.registerAllowedMethod("credentials", [String]) {it -> it}

        when: "running buildGradlePlugin pipeline"
        buildGradlePlugin()

        then: "sets up GRGIT environment"
        def env = buildGradlePlugin.binding.env
        env["GRGIT"] == 'github_up'
        env["GRGIT_USER"] == "usr" //"${GRGIT_USR}"
        env["GRGIT_PASS"] == "pwd" //"${GRGIT_PSW}"
        and: "sets up github environment"
        env["GITHUB_LOGIN"] == "usr" //"${GRGIT_USR}"
        env["GITHUB_PASSWORD"] == "pwd" //"${GRGIT_PSW}"
    }
}
