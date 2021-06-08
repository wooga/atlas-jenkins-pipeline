package specs

import spock.lang.Shared
import tools.DeclarativeJenkinsSpec

class BuildGradlePluginSpec extends DeclarativeJenkinsSpec {

    @Shared Script gradleWrapperScript;

    def setupSpec() {
        binding.setVariable("params", [RELEASE_TYPE: "snapshot"])
        this.gradleWrapperScript = helper.loadScript("vars/gradleWrapper.groovy", binding)
        print this.gradleWrapperScript
    }

    def "should execute sonar when sonar token is present" () {
        given: "loaded buildGradlePlugin in a running build"
        binding.getVariable("currentBuild").with { result = null }
        helper.registerAllowedMethod("gradleWrapper", [String]) { command ->
            this.gradleWrapperScript(command)
        }
        def buildGradlePlugin = helper.loadScript("vars/buildGradlePlugin.groovy", binding)

        and: "a token for accessing SonarQube"
        def sonarToken = "token"

        and: "a unix-like platform"
        helper.registerAllowedMethod("isUnix") {true}

        when: "running gradle pipeline with sonarToken parameter"
        buildGradlePlugin(sonarToken: sonarToken)

        then: "gradle sonarqube task is called"
        hasShCallWith{ callString ->
                    callString.contains("gradlew") &&
                    callString.contains("sonarqube") &&
                    callString.contains("-Dsonar.login=${sonarToken}")
        }
    }

}
