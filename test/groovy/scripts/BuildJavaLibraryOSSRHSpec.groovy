package scripts

import com.lesfurets.jenkins.unit.MethodCall
import spock.lang.Unroll
import tools.DeclarativeJenkinsSpec

class BuildJavaLibraryOSSRHSpec extends DeclarativeJenkinsSpec {
    private static final String SCRIPT_PATH = "vars/buildJavaLibraryOSSRH.groovy"

    def setupSpec() {
        binding.setVariable("params", [RELEASE_TYPE: "snapshot"])
        binding.setVariable("BRANCH_NAME", "any")
    }

    def "posts coveralls results to coveralls server" () {
        given: "loaded buildJavaLibrary in a successful build"
        helper.registerAllowedMethod("httpRequest", [LinkedHashMap]) {}
        def buildJavaLibrary = loadScript(SCRIPT_PATH) {
            currentBuild["result"] = "SUCCESS"
        }

        and: "a coveralls token"
        def coverallsToken = "token"

        when: "running gradle pipeline with coverallsToken parameter"
        buildJavaLibrary(coverallsToken: coverallsToken)

        then: "request is sent to coveralls webhook"
        calls.has["httpRequest"]{ MethodCall call ->
            Map params = call.args[0] as Map
            return params["httpMode"] == "POST" &&
                    params["ignoreSslErrors"] == true &&
                    params["url"] == "https://coveralls.io/webhook?repo_token=${coverallsToken}"
        }
    }

    @Unroll("publishes #releaseType-#releaseScope release ")
    def "publishes with #release release type"() {
        given: "credentials holder with ossrh publish keys"
        credentials.addUsernamePassword('ossrh.publish', "user", "key")
        credentials.addString('ossrh.signing.key', "signingKey")
        credentials.addString('ossrh.signing.key_id', "keyId")
        credentials.addString('ossrh.signing.passphrase', "passphrase")
        and: "build plugin with publish parameters"
        def buildJavaLibrary = loadScript(SCRIPT_PATH) {
            currentBuild["result"] = null
            params.RELEASE_TYPE = releaseType
            params.RELEASE_SCOPE = releaseScope
        }

        when: "running buildJavaLibrary pipeline"
        buildJavaLibrary()

        then: "runs gradle with parameters"
        skipsRelease ^/*XOR*/ calls.has["sh"] { MethodCall call ->
            String it = call.args[0]["script"]
            it.contains("gradlew") &&
            it.contains(releaseType) &&
            it.contains("-Prelease.stage=${releaseType}") &&
            it.contains("-Prelease.scope=${releaseScope}") &&
            it.contains("-x check")
        }

        where:
        releaseType | releaseScope | skipsRelease
        "snapshot"  | "patch"      | true
        "rc"        | "minor"      | false
        "final"     | "major"      | false
    }

    def "registers environment on publish"() {
        given: "credentials holder with bintray publish keys"
        credentials.addUsernamePassword('github_access', "usr", "pwd")
        credentials.addUsernamePassword('bintray.publish', "user", "key")
        and: "build plugin with publish parameters"
        def buildJavaLibrary = loadScript(SCRIPT_PATH) {
            currentBuild["result"] = null
            params.RELEASE_TYPE = "not-snapshot"
            params.RELEASE_SCOPE = "any"
            env.GRGIT_USR = "usr"
            env.GRGIT_PSW = "pwd"
        }

        when: "running buildJavaLibrary pipeline"
        buildJavaLibrary()

        then: "sets up GRGIT environment"
        def env = buildJavaLibrary.binding.env
        env["GRGIT"] == credentials['github_access']
        env["GRGIT_USER"] == "usr" //"${GRGIT_USR}"
        env["GRGIT_PASS"] == "pwd" //"${GRGIT_PSW}"
        and: "sets up github environment"
        env["GITHUB_LOGIN"] == "usr" //"${GRGIT_USR}"
        env["GITHUB_PASSWORD"] == "pwd" //"${GRGIT_PSW}"
    }
}
