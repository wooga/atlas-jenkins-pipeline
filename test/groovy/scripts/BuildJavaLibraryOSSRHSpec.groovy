package scripts

import com.lesfurets.jenkins.unit.MethodCall
import spock.lang.Unroll
import tools.DeclarativeJenkinsSpec
import tools.GradleInvocation

class BuildJavaLibraryOSSRHSpec extends DeclarativeJenkinsSpec {
    private static final String SCRIPT_PATH = "vars/buildJavaLibraryOSSRH.groovy"

    def setupSpec() {
        binding.setVariable("params", [RELEASE_TYPE: "snapshot"])
        binding.setVariable("BRANCH_NAME", "any")
    }

    def "posts coveralls results to coveralls server"() {
        given: "loaded buildJavaLibrary in a successful build"
        helper.registerAllowedMethod("httpRequest", [LinkedHashMap]) {}
        def buildJavaLibrary = loadSandboxedScript(SCRIPT_PATH) {
            currentBuild["result"] = "SUCCESS"
        }

        and: "a coveralls token"
        def coverallsToken = "token"

        when: "running gradle pipeline with coverallsToken parameter"
        inSandbox { buildJavaLibrary(coverallsToken: coverallsToken) }

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
        given: "credentials holder with ossrh publish keys"
        credentials.addUsernamePassword('ossrh.publish', "user", "key")
        credentials.addString('ossrh.signing.key', "signingKey")
        credentials.addString('ossrh.signing.key_id', "keyId")
        credentials.addString('ossrh.signing.passphrase', "passphrase")
        and: "build plugin with publish parameters"
        def buildJavaLibrary = loadSandboxedScript(SCRIPT_PATH) {
            currentBuild["result"] = null
            params.RELEASE_TYPE = releaseType
            params.RELEASE_SCOPE = releaseScope
        }

        when: "running buildJavaLibrary pipeline"
        inSandbox { buildJavaLibrary() }

        then: "runs gradle with parameters"
        def gradleCall = getShGradleCalls().first()
        skipsRelease || (gradleCall != null)
        def publishCall = GradleInvocation.parseCommandLine(gradleCall)
        skipsRelease ^ publishCall.tasks.contains(releaseType?: "snapshot")
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
        given: "credentials holder with bintray publish keys"
        credentials.addUsernamePassword('github_access', "usr", "pwd")
        credentials.addUsernamePassword('ossrh.publish', "user", "key")
        credentials.addString('ossrh.signing.key', "signing_key")
        credentials.addString('ossrh.signing.key_id', "signing_key_id")
        credentials.addString('ossrh.signing.passphrase', "signing_passwd")
        and: "build plugin with publish parameters"
        def buildJavaLibrary = loadSandboxedScript(SCRIPT_PATH) {
            currentBuild["result"] = null
            params.RELEASE_TYPE = "not-snapshot"
            params.RELEASE_SCOPE = "any"
            env.GRGIT_USR = "usr"
            env.GRGIT_PSW = "pwd"
        }

        when: "running buildJavaLibrary pipeline"
        inSandbox { buildJavaLibrary() }

        then: "sets up GRGIT environment"
        def env = buildJavaLibrary.binding.env
        env["GRGIT"] == credentials['github_access']
        env["GRGIT_USER"] == "usr" //"${GRGIT_USR}"
        env["GRGIT_PASS"] == "pwd" //"${GRGIT_PSW}"
        and: "sets up github environment"
        env["GITHUB_LOGIN"] == "usr" //"${GRGIT_USR}"
        env["GITHUB_PASSWORD"] == "pwd" //"${GRGIT_PSW}"
        and: "sets up ossrh keys"
        def publishEnv = usedEnvironments.last()
        publishEnv["OSSRH_USERNAME"] == "user"
        publishEnv["OSSRH_PASSWORD"] == "key"
        publishEnv["OSSRH_SIGNING_KEY"] == "signing_key"
        publishEnv["OSSRH_SIGNING_KEY_ID"] == "signing_key_id"
        publishEnv["OSSRH_SIGNING_PASSPHRASE"] == "signing_passwd"
    }
}
