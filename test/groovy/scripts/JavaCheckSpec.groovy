package scripts

import com.lesfurets.jenkins.unit.MethodCall
import net.wooga.jenkins.pipeline.config.Config
import net.wooga.jenkins.pipeline.config.Platform
import spock.lang.Unroll
import tools.DeclarativeJenkinsSpec

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger


class JavaCheckSpec extends DeclarativeJenkinsSpec {
    private static final String TEST_SCRIPT_PATH = "test/resources/scripts/checkTest.groovy"

    def setupSpec() {
    }


    @Unroll("execute #name if their token(s) are present")
    def "execute coverage when its token is present" () {
        given: "loaded check in a running build"
        def check = loadScript(TEST_SCRIPT_PATH) {
            currentBuild["result"] = null
        }
        and: "configuration in the master branch and with tokens"
        def config = Config.fromConfigMap(
                [sonarToken: sonarToken, coverallsToken: coverallsToken],
                [BUILD_NUMBER: 1, BRANCH_NAME: "master"]
        )

        when: "running gradle pipeline with coverage token"
        check(config).javaCoverage(config, false).each {it.value()}

        then: "gradle coverage task is called"
        gradleCmdElements.every { it -> it.every {element ->
            calls.has["sh"] { MethodCall call ->
                String args = call.args[0]["script"]
                args.contains("gradlew") && args.contains(element)
            }
        }}

        where:
        name                    | gradleCmdElements                                          | sonarToken   | coverallsToken
        "SonarQube"             | [["sonarqube", "-Dsonar.login=sonarToken"]]                | "sonarToken" | null
        "Coveralls"             | [["coveralls"]]                                            | null         | "coverallsToken"
        "SonarQube & Coveralls" | [["coveralls"], ["sonarqube", "-Dsonar.login=sonarToken"]] | "sonarToken" | "coverallsToken"
    }

    @Unroll("should execute sonarqube on branch #branchName")
    def "should execute sonarqube in PR and non-PR branches with correct arguments"() {
        given: "loaded build script in a running build in branch"
        def check = loadScript(TEST_SCRIPT_PATH) {
            if (isPR) {
                CHANGE_ID = "notnull"
            }
        }
        and: "configuration in the ${branchName} branch with token"
        def config = Config.fromConfigMap(
                [sonarToken: "sonarToken"],
                [BUILD_NUMBER: 1, BRANCH_NAME: branchName]
        )

        when: "running gradle pipeline with sonar token"
        check(config).javaCoverage(config, isPR).each {it.value()}

        then: "should run sonar analysis"
        calls.has["sh"] {  MethodCall call ->
            String callString = call.args[0]["script"]
            callString.contains("gradlew") &&
                    callString.contains("sonarqube") &&
                    callString.contains("-Dsonar.login=sonarToken") &&
                    callString.contains("-Pgithub.branch.name=${expectedBranchProperty}")
        }

        where:
        branchName   | prBranch | isPR  | expectedBranchProperty
        "master"     | null     | false | "master"
        "not_master" | null     | false | "not_master"
        "PR-123"     | "branch" | true  | ""
        "branchpr"   | "branch" | true  | ""
    }

    @Unroll("runs check step for #platforms")
    def "runs check step for all given platforms"() {
        given: "loaded check in a running jenkins build"
        def check = loadScript(TEST_SCRIPT_PATH)
        and:"configuration object with given platforms"
        def config = Config.fromConfigMap([platforms: platforms], [BUILD_NUMBER: 1])

        when: "running check"
        def checkSteps = check(config).javaCoverage(config, false) as Map<String, Closure>
        checkSteps.each {it.value.call()}

        then: "platform check registered on parallel operation"
        calls["checkout"].length == platforms.size()
        checkSteps.collect {
            it -> it.key.replace("check", "").trim()
        } == platforms
        calls["sh"].count {
            def argsString = it.argsToString()
            argsString.contains("gradlew") && argsString.contains("check")
        } == platforms.size()
        where:
        platforms << [
        ["macos"], ["windows"], ["linux"],
        ["macos", "linux"], ["windows", "linux"], ["windows", "macos"]]
    }

    @Unroll
    def "runs dockerized check for linux"() {
        given: "check loaded in a jenkins build"
        and: "a fake dockerfile"
        createTmpFile(dockerDir, dockerfile)
        and: "a mocked jenkins docker object"
        def dockerMock = createDockerMock(dockerfile, image, dockerDir, dockerBuildArgs, dockerArgs)
        def check = loadScript(TEST_SCRIPT_PATH) {
            docker = dockerMock
        }
        and: "linux configuration object with docker args"
        def config = Config.fromConfigMap([
                platforms : ["linux"],
                dockerArgs: [image              : image, dockerFileName: dockerfile,
                             dockerFileDirectory: dockerDir, dockerBuildArgs: dockerBuildArgs, dockerArgs: dockerArgs]],
                [BUILD_NUMBER: 1])
        when: "running linux platform step"
        def checkSteps = check(config).javaCoverage(config, false) as Map<String, Closure>
        checkSteps["check linux"].call()

        then:
        dockerMock.ran.get() && calls.has["sh"] { MethodCall call ->
            String args = call.args[0]["script"]
            args.contains("gradlew") && args.contains("check")
        }

        where:
        dockerfile   | image   | dockerDir   | dockerBuildArgs  | dockerArgs
        null         | "image" | null        | null             | ["arg1"]
        "dockerfile" | null    | "dockerDir" | ["arg1", "arg2"] | ["arg1"]
        "dockerfile" | "image" | "dockerDir" | ["arg1", "arg2"] | ["arg1"]
    }


    def "doesnt runs analysis twice on parallel check run"() {
        given: "loaded check in a running jenkins build"
        def check = loadScript(TEST_SCRIPT_PATH) {
            currentBuild["result"] = null
        }
        and:"configuration object with more than one platform"
        def config = Config.fromConfigMap([platforms: ["plat1", "plat2"]], [BUILD_NUMBER: 1])
        and: "generated check steps"
        def testCount = new AtomicInteger(0)
        def analysisCount = new AtomicInteger(0)
        Map<String, Closure> steps = check(config).simple(config,
                { testCount.incrementAndGet() },
                { analysisCount.incrementAndGet() }
        )

        when: "running steps on parallel"
        CompletableFuture<Void>[] futures = steps.
                collect {CompletableFuture.runAsync(it.value)}.
                toArray(new CompletableFuture<Void>[0])
        CompletableFuture.allOf(futures).get() //wait for futures to be completed

        then: "test step ran for all platforms"
        testCount.get() == config.platforms.length
        and: "analysis step ran only once"
        analysisCount.get() == 1
    }

    @Unroll
    def "loads test environment on check"() {
        given: "loaded check in a running jenkins build"
        def check = loadScript(TEST_SCRIPT_PATH) {
            BUILD_NUMBER=1
        }
        and:"configuration object with more than one platform"
        def config = Config.fromConfigMap([platforms: platforms, testEnvironment:testEnvironment], [BUILD_NUMBER: 1])
        and: "generated check steps"
        Map<String, Map> checkEnvMap = platforms.collectEntries{[(it): [:]]}
        Map<String, Map> analysisEnvMap = platforms.collectEntries{[(it): [:]]}
        Map<String, Closure> steps = check(config).simple(config,
                { Platform plat ->
                    binding.env.every {checkEnvMap[plat.name][it.key] = it.value }
                },
                { Platform plat ->
                    binding.env.every { analysisEnvMap[plat.name][it.key] = it.value }
                }
        )

        when: "running steps"
        steps.each {it.value.call()}

        then: "test step ran for all platforms"
        checkEnvMap.every { platEnv ->
            platEnv.value["TRAVIS_JOB_NUMBER"] == "${config.metadata.buildNumber}.${platEnv.key.toUpperCase()}"
        }
        expectedInEnvironment.every {expPlatEnv ->
            def actualPlatEnv = checkEnvMap[expPlatEnv.key]
            actualPlatEnv.entrySet().containsAll(expPlatEnv.value.entrySet())
        }
        //analysis only runs once, so we only assert for first platform
        def firstPlat = platforms[0]
        def actualAnalysisPlatEnv = analysisEnvMap[firstPlat]
        actualAnalysisPlatEnv.entrySet().containsAll(expectedInEnvironment[firstPlat].entrySet())

        where:
        platforms          | testEnvironment         | expectedInEnvironment
        ["plat1"]          | ["a=b", "c=d"]          | [plat1: [a: "b", c: "d"]]
        ["plat1"]          | [plat1: ["a=b", "c=d"]] | [plat1: [a: "b", c: "d"]]
        ["plat1", "plat2"] | ["a=b", "c=d"]          | [plat1: [a: "b", c: "d"], plat2: [a: "b", c: "d"]]
        ["plat1", "plat2"] | [plat2: ["a=b", "c=d"]] | [plat1: [:], plat2: [a: "b", c: "d"]]
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

    def createDockerMock(String dockerfile, String image, String dockerDir,
                         List<String> dockerBuildArgs, List<String> dockerArgs) {
        AtomicBoolean ran = new AtomicBoolean(false)
        def imgMock = [inside: {args, cls ->
            if(args==dockerArgs.join(" ")) {
                ran.set(true)
                cls()
            }
        }]
        def buildArgs = "-f ${dockerfile} ${dockerBuildArgs} ${dockerDir}"
        return [image: {name -> name==image? imgMock: null},
                build: {hash, args -> args == buildArgs? imgMock : null},
                ran: ran]
    }
}
