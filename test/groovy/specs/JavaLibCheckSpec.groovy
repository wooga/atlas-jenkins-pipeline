package specs


import net.wooga.jenkins.pipeline.config.Config
import spock.lang.Unroll
import tools.DeclarativeJenkinsSpec

import java.nio.file.Files
import java.nio.file.Paths


class JavaLibCheckSpec extends DeclarativeJenkinsSpec {
    private static final String SCRIPT_PATH = "src/net/wooga/jenkins/pipeline/scripts/javaLibCheck.groovy"

    def setupSpec() {
        helper.registerAllowedMethod("isUnix") { true }
    }


    @Unroll("execute #name if their token(s) are present")
    def "execute coverage when its token is present" () {
        given: "loaded buildJavaLib in a running build"
        def buildJavaLib = loadScript(SCRIPT_PATH) {
            currentBuild["result"] = null
        }
        and: "configuration in the master branch and with tokens"
        def config = Config.fromConfigMap(
                [sonarToken: sonarToken, coverallsToken: coverallsToken],
                [BUILD_NUMBER: 1, BRANCH_NAME: "master"]
        )

        when: "running gradle pipeline with coverage token"
        buildJavaLib(config).checkStepsWithCoverage(false).each {it.value()}

        then: "gradle coverage task is called"
        gradleCmdElements.every { it -> it.every {element ->
            hasShCallWith {
                call -> call.contains("gradlew") && call.contains(element)
            }
        }}

        where:
        name                    | gradleCmdElements                                          | sonarToken   | coverallsToken
        "SonarQube"             | [["sonarqube", "-Dsonar.login=sonarToken"]]                | "sonarToken" | null
//        "Coveralls"             | [["coveralls"]]                                            | null         | "coverallsToken"
//        "SonarQube & Coveralls" | [["coveralls"], ["sonarqube", "-Dsonar.login=sonarToken"]] | "sonarToken" | "coverallsToken"
    }

    @Unroll("#shouldRunSonar execute sonarqube if #branchName matches pattern when force is #forceSonar")
    def "Only executes sonarqube in branches matching pattern unless sonarqube is forced" () {
        given: "loaded buildJavaLib in a running jenkins build"
        def buildJavaLib = loadScript(SCRIPT_PATH) {
            currentBuild["result"] = null
        }
        and: "configuration in the ${branchName} branch with token"
        and: "sonarQubeBranchPattern config set to ${branchPattern?: "default (^main|master\$)"}"
        def config = Config.fromConfigMap(
                [sonarToken: "sonarToken", sonarQubeBranchPattern: branchPattern],
                [BUILD_NUMBER: 1, BRANCH_NAME: branchName]
        )

        when: "running gradle pipeline with coverage token"
        buildJavaLib(config).checkStepsWithCoverage(forceSonar).each {it.value()}

        then: "${shouldRunSonar} run sonar analysis"
        def sonarCalled = hasShCallWith { callString ->
            callString.contains("gradlew") &&
                    callString.contains("sonarqube") &&
                    callString.contains("-Dsonar.login=sonarToken")
        }
        shouldRunSonar == "should"? sonarCalled : !sonarCalled

        where:
        branchName              | branchPattern | forceSonar    | shouldRunSonar
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

    @Unroll("runs check step for #platforms")
    def "runs check step for all given platforms"() {
        given: "loaded buildJavaLib in a running jenkins build"
        def buildJavaLib = loadScript(SCRIPT_PATH)
        and:"configuration object with given platforms"
        def config = Config.fromConfigMap([platforms: platforms], [BUILD_NUMBER: 1])

        when: "running buildJavaLib"
        def checkSteps = buildJavaLib(config).checkStepsWithCoverage(false) as Map<String, Closure>
        checkSteps.each {it.value.call()}

        then: "platform check registered on parallel operation"
        checkSteps.collect {
            it -> it.key.replace("check", "").trim()
        } == platforms
        getMethodCalls("sh").count {
            def argsString = it.argsToString()
            argsString.contains("gradlew") && argsString.contains("check")
        } == platforms.size()
        where:
        platforms << [
        ["macos"], ["windows"], ["linux"],
        ["macos, linux"], ["windows, linux"], ["windows, macos"]]
    }

    @Unroll
    def "runs dockerized check for linux"() {
        given: "buildJavaLib loaded in a jenkins build"
        and: "a fake dockerfile"
        createTmpFile(dockerDir, dockerfile)
        and: "a mocked jenkins docker object"
        def ran = false
        def imgMock = [inside: {args, cls ->
            if(args==dockerArgs.join(" ")) {
                ran = true
                cls()
            }
        }]
        def buildArgs = "-f ${dockerfile} ${dockerBuildArgs} ${dockerDir}"
        def dockerMock = [image: {name -> name==image? imgMock: null},
                          build: {hash, args -> args == buildArgs? imgMock : null}]
        def buildJavaLib = loadScript(SCRIPT_PATH) {
            docker = dockerMock
        }
        and:"linux configuration object with docker args"
        def config = Config.fromConfigMap([
                platforms: ["linux"],
                dockerArgs: [image: image, dockerFileName: dockerfile,
                             dockerFileDirectory: dockerDir, dockerBuildArgs: dockerBuildArgs, dockerArgs: dockerArgs]],
        [BUILD_NUMBER: 1])
        when: "running linux platform step"
        def checkSteps = buildJavaLib(config).checkStepsWithCoverage(false) as Map<String, Closure>
        checkSteps["check linux"].call()

        then:
        ran && hasShCallWith {it.contains("gradlew") && it.contains("check")}

        where:
        dockerfile   | image   | dockerDir   | dockerBuildArgs  | dockerArgs
        null         | "image" | null        | null             | ["arg1"]
        "dockerfile" | null    | "dockerDir" | ["arg1", "arg2"] | ["arg1"]
        "dockerfile" | "image" | "dockerDir" | ["arg1", "arg2"] | ["arg1"]
    }

    def createTmpFile(String dir=".", String file) {
        if(file != null) {
            new File(dir).mkdirs()
            new File(dir, file).with {
                createNewFile()
                deleteOnExit()
            }
        }
    }
}
