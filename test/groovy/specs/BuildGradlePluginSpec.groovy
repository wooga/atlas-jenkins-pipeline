package specs

import com.lesfurets.jenkins.unit.MethodCall
import spock.lang.Shared
import spock.lang.Unroll
import tools.DeclarativeJenkinsSpec

class BuildGradlePluginSpec extends DeclarativeJenkinsSpec {

    @Shared Script gradleWrapperScript;

    def setupSpec() {
        binding.setVariable("params", [RELEASE_TYPE: "snapshot"])
        helper.registerAllowedMethod("isUnix") { true }
        this.gradleWrapperScript = helper.loadScript("vars/gradleWrapper.groovy", binding)
        helper.registerAllowedMethod("gradleWrapper", [String]) { command ->
            this.gradleWrapperScript.call(command)
        }
    }

    def "should post coveralls results to coveralls server" () {
        given: "loaded buildGradlePlugin in a successful build"
        helper.registerAllowedMethod("httpRequest", [LinkedHashMap]) {}
        def buildGradlePlugin = loadScript("vars/buildGradlePlugin.groovy") {
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

    @Unroll("should execute #name if their token(s) are present")
    def "should execute coverage when its token is present" (){
        given: "loaded buildGradlePlugin in a running build in the master branch"
        def buildGradlePlugin = loadScript("vars/buildGradlePlugin.groovy") {
            BRANCH_NAME = "master"
            currentBuild["result"] = null
        }

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

    @Unroll("#shouldRunSonar execute sonarqube if #branchName matches pattern when RUN_SONAR is #runSonarParam")
    def "should only execute sonarqube in branches matching pattern unless RUN_SONAR is true" () {
        given: "loaded build script in a running build in the ${branchName} branch"
        def buildGradlePlugin = loadScript("vars/buildGradlePlugin.groovy") {
            BRANCH_NAME = branchName
            currentBuild["result"] = null
        }

        and: "RUN_SONAR set to ${runSonarParam}"
        binding.getVariable("params").with { RUN_SONAR = runSonarParam }

        and: "sonarQubeBranchPattern config set to ${branchPattern?: "default (^main|master\$)"}"
        def runConfig = [sonarToken: "sonarToken", sonarQubeBranchPattern: branchPattern]

        when: "running gradle pipeline with sonar token"
        buildGradlePlugin(runConfig)

        then: "${shouldRunSonar} run sonar analysis"
        def sonarCalled = hasShCallWith { callString ->
            callString.contains("gradlew") &&
                    callString.contains("sonarqube") &&
                    callString.contains("-Dsonar.login=sonarToken")
        }
        shouldRunSonar == "should"? sonarCalled : !sonarCalled

        where:
        branchName              | branchPattern | runSonarParam | shouldRunSonar
        "master"                | null          | true          | "should"
        "master"                | null          | false         | "should"
        "master"                | "^nomaster\$" | false         | "shouldn't"
        "main"                  | null          | true          | "should"
        "main"                  | null          | false         | "should"
        "main"                  | "^nomaster\$" | false         | "shouldn't"
        "nomaster"              | null          | true          | "should"
        "nomaster"              | "^nomaster\$" | false         | "should"
        "nomaster"              | null          | false         | "shouldn't"
    }
}
