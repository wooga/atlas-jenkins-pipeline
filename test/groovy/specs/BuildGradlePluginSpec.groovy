package specs

import com.lesfurets.jenkins.unit.MethodCall
import spock.lang.Shared
import spock.lang.Unroll
import tools.DeclarativeJenkinsSpec

class BuildGradlePluginSpec extends DeclarativeJenkinsSpec {

    @Shared Script gradleWrapperScript;

    def setupSpec() {
        binding.setVariable("params", [RELEASE_TYPE: "snapshot"])
        this.gradleWrapperScript = helper.loadScript("vars/gradleWrapper.groovy", binding)
    }

    @Unroll("should execute #name if their token(s) are present")
    def "should execute coverage when its token is present" (){
        given: "loaded buildGradlePlugin in a running build"
        binding.getVariable("currentBuild").with { result = null }
        helper.registerAllowedMethod("gradleWrapper", [String]) { command ->
            this.gradleWrapperScript.call(command)
        }
        def buildGradlePlugin = helper.loadScript("vars/buildGradlePlugin.groovy", binding)

        and: "a unix-like platform"
        helper.registerAllowedMethod("isUnix") {true}

        when: "running gradle pipeline with coverage token"
        buildGradlePlugin(sonarToken: sonarToken, coverallsToken: coverallsToken)

        then: "gradle coverage task is called"
        hasShCallWith{ callString ->
            callString.contains("gradlew") &&
                    gradleCmdElements.every {callString.contains(it) }
        }

        where:
        name                    | gradleCmdElements                                        | sonarToken   | coverallsToken
        "SonarQube"             | ["sonarqube", "-Dsonar.login=sonarToken"]                | "sonarToken" | null
        "Coveralls"             | ["coveralls"]                                            | null         | "coverallsToken"
        "SonarQube & Coveralls" | ["coveralls", "sonarqube", "-Dsonar.login=sonarToken"]   |"sonarToken"  | "coverallsToken"
    }

    def "should post coveralls results to coveralls server" () {
        given: "loaded buildGradlePlugin in a successful build"
        binding.getVariable("currentBuild").with { result = "SUCCESS" }
        helper.registerAllowedMethod("httpRequest", [LinkedHashMap]) {}
        helper.registerAllowedMethod("gradleWrapper", [String]) { command ->
            this.gradleWrapperScript.call(command)
        }
        def buildGradlePlugin = helper.loadScript("vars/buildGradlePlugin.groovy", binding)

        and: "a coveralls token"
        def coverallsToken = "token"

        and: "a unix-like platform"
        helper.registerAllowedMethod("isUnix") {true}

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
}
