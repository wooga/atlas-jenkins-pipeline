package scripts

import com.lesfurets.jenkins.unit.MethodCall
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
    def "execute coverage when its token is present"() {
        given: "loaded check in a running build"
        def check = loadSandboxedScript(TEST_SCRIPT_PATH)

        and: "configuration in the master branch and with tokens"
        def configMap = [sonarToken: sonarToken, coverallsToken: coverallsToken]
        def jenkinsMeta = [BUILD_NUMBER: 1, BRANCH_NAME: "master"]

        when: "running gradle pipeline with coverage token"
        inSandbox {
            //class loaders are ~~hell~~ FUN
            check.javaCoverage(configMap, jenkinsMeta).each { it.value() }
        }

        then: "gradle coverage task is called"
        gradleCmdElements.every { it ->
            it.every { element ->
                calls.has["sh"] { MethodCall call ->
                    String args = call.args[0]["script"]
                    args.contains("gradlew") && args.contains(element)
                }
            }
        }

        where:
        name                    | gradleCmdElements                                          | sonarToken   | coverallsToken
        "SonarQube"             | [["sonarqube", "-Dsonar.login=sonarToken"]]                | "sonarToken" | null
        "Coveralls"             | [["coveralls"]]                                            | null         | "coverallsToken"
        "SonarQube & Coveralls" | [["coveralls"], ["sonarqube", "-Dsonar.login=sonarToken"]] | "sonarToken" | "coverallsToken"
    }

    @Unroll("should execute sonarqube on branch #branchName")
    def "should execute sonarqube in PR and non-PR branches with correct arguments"() {
        given: "loaded build script in a running build in branch"
        def check = loadSandboxedScript(TEST_SCRIPT_PATH)

        and: "jenkins script object with needed properties"
        def jenkinsMeta = [BUILD_NUMBER: 1, BRANCH_NAME: branchName, env: [:]]
        if (isPR) {
            jenkinsMeta.CHANGE_ID = "notnull"
            jenkinsMeta.env["CHANGE_ID"] = "notnull"
        }
        and:
        "configuration in the ${branchName} branch with token"
        def configMap = [sonarToken: "sonarToken"]

        when: "running gradle pipeline with sonar token"
        inSandbox { check.javaCoverage(configMap, jenkinsMeta).each { it.value() } }

        then: "should run sonar analysis"
        calls.has["sh"] { MethodCall call ->
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
        def check = loadSandboxedScript(TEST_SCRIPT_PATH)
        and: "configuration object with given platforms"
        def configMap = [platforms: platforms]

        when: "running check"

        Map<String, ?> checkSteps = inSandbox {
            def checkSteps = check.javaCoverage(configMap, [BUILD_NUMBER: 1])
            checkSteps.each { it.value.call() }
            return checkSteps
        }

        then:
        calls["checkout"].length == platforms.size()
        checkSteps != null
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
        def check = loadSandboxedScript(TEST_SCRIPT_PATH) {
            docker = dockerMock
        }
        and: "linux configuration object with docker args"
        def configMap = [
                platforms : ["linux"],
                dockerArgs: [image              : image, dockerFileName: dockerfile,
                             dockerFileDirectory: dockerDir, dockerBuildArgs: dockerBuildArgs, dockerArgs: dockerArgs]
        ]
        when: "running linux platform step"
        inSandbox {
            def checkSteps = check.javaCoverage(configMap, [BUILD_NUMBER: 1])
            checkSteps["check linux"].call()
        }


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
        def check = loadSandboxedScript(TEST_SCRIPT_PATH)
        and: "configuration object with more than one platform"
        def configMap = [platforms: ["plat1", "plat2"]]
        and: "generated check steps"
        String analysisPlatform = null
        def testCount = new AtomicInteger(0)
        def analysisCount = new AtomicInteger(0)
        Map<String, Closure> steps = inSandbox {
            return check.parallel(configMap, [BUILD_NUMBER: 1],
                    { _, __ ->
                        testCount.incrementAndGet()
                    },
                    { platform, _ ->
                        analysisPlatform = platform.name
                        analysisCount.incrementAndGet()
                    }
            )
        }

        when: "running steps on parallel"
        CompletableFuture<Void>[] futures = steps.
                collect { entry -> CompletableFuture.runAsync({ inSandbox(entry.value) }) }.
                toArray(new CompletableFuture<Void>[0])
        CompletableFuture.allOf(futures).get() //wait for futures to be completed

        then: "test step ran for all platforms"
        testCount.get() == configMap["platforms"].size()
        and: "analysis step ran only once"
        analysisCount.get() == 1
        and: "analysis step ran on first platform"
        analysisPlatform == configMap.platforms[0]
    }

    @Unroll
    def "loads test environment on check"() {
        given: "loaded check in a running jenkins build"
        def check = loadSandboxedScript(TEST_SCRIPT_PATH) {
            BUILD_NUMBER = 1
        }
        and: "configuration object with more than one platform"
        def configMap = [platforms: platforms, testEnvironment: testEnvironment]
        def jenkinsMeta = [BUILD_NUMBER: 1]

        when: "running check steps"
        Map<String, Map> checkEnvMap = platforms.collectEntries { [(it): [:]] }
        Map<String, Map> analysisEnvMap = platforms.collectEntries { [(it): [:]] }
        inSandbox {
            Map<String, Closure> steps = check.parallel(configMap, jenkinsMeta,
                    { plat, _ ->
                        binding.env.every { entry -> checkEnvMap[plat.name][entry.key] = entry.value }
                    },
                    { plat, _ ->
                        binding.env.every { entry -> analysisEnvMap[plat.name][entry.key] = entry.value }
                    })
            steps.each { entry -> entry.value.call() }
        }

        then: "test step ran for all platforms"
        checkEnvMap.every { platEnv ->
            platEnv.value["TRAVIS_JOB_NUMBER"] == "${jenkinsMeta["BUILD_NUMBER"]}.${platEnv.key.toUpperCase()}"
        }
        expectedInEnvironment.every { expPlatEnv ->
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

    def "applies convention to check"() {
        given: "loaded check in a running jenkins build"
        def check = loadSandboxedScript(TEST_SCRIPT_PATH)
        and: "configuration object with any platforms"
        def configMap = [platforms: ["linux"], sonarToken: "token", coverallsToken: "token"]

        when: "running check"
        Map<String, ?> checkSteps = inSandbox {
            def config = check.getConfig(configMap, [BUILD_NUMBER: 1])
            def checks = check(config)
            //Closure delegate object -> JavaCheckParams
            Map<String, Closure> checkSteps = checks.javaCoverage(config) {
                conventions.checkTask = convCheck
                conventions.sonarqubeTask = convSonarqubeTask
                conventions.jacocoTask = convJacocoTask
                conventions.javaParallelPrefix = convJavaParallelPrefix
                conventions.coverallsTask = convCoverallsTask
            }
            checkSteps.each { it.value.call() }
            return checkSteps
        }

        then:
        checkSteps != null
        checkSteps.every {
            it -> it.key.startsWith(convJavaParallelPrefix)
        }
        calls["sh"].count {
            def argsString = it.argsToString()
            argsString.contains("gradlew") && argsString.contains(convCheck)
        } == 1
        calls["sh"].count {
            def argsString = it.argsToString()
            argsString.contains("gradlew") && argsString.contains(convSonarqubeTask)
        } == 1
        calls["sh"].count {
            def argsString = it.argsToString()
            argsString.contains("gradlew") && argsString.contains(convCoverallsTask)
        } == 1
        calls["sh"].count {
            def argsString = it.argsToString()
            argsString.contains("gradlew") && argsString.contains(convJacocoTask)
        } == 1
        where:
        convCheck  | convSonarqubeTask | convCoverallsTask | convJacocoTask | convJavaParallelPrefix
        "otherchk" | "othersq"         | "othercv"         | "otherjc"      | "othercheck"
    }

    def "wraps check and analysis step on closure wrapper"() {
        given: "loaded check in a running jenkins build"
        def check = loadSandboxedScript(TEST_SCRIPT_PATH)
        and: "configuration object with any platforms"
        def configMap = [platforms: ["linux", "windows"], sonarToken: "token", coverallsToken: "token"]

        when: "running check"
        def testCount = new AtomicInteger(0)
        def analysisCount = new AtomicInteger(0)
        inSandbox {
            def config = check.getConfig(configMap, [BUILD_NUMBER: 1])
            def checks = check(config)
            //Closure delegate object -> JavaCheckParams
            Map<String, Closure> checkSteps = checks.javaCoverage(config) {
                testWrapper = { testOp, platform, gradle ->
                    testCount.incrementAndGet()
                    testOp(platform, gradle)
                }
                analysisWrapper = { analysisOp, platform, gradle ->
                    analysisCount.incrementAndGet()
                    analysisOp(platform, gradle)
                }
            }
            checkSteps.each { it.value.call() }
        }

        then: "wrapper test step ran for all platforms"
        def platformCount = configMap["platforms"].size()
        testCount.get() == platformCount
        and: "inner test step ran for all platforms"
        calls["sh"].count {
            def argsString = it.argsToString()
            argsString.contains("gradlew") && argsString.contains("check")
        } == platformCount
        and: "wrapper analysis step ran only once"
        analysisCount.get() == 1
        and: "inner analysis step ran only once"
        calls["sh"].count {
            def argsString = it.argsToString()
            argsString.contains("gradlew") && argsString.contains("sonarqube")
        } == 1
    }

    def createTmpFile(String dir = ".", String file) {
        if (file != null) {
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
        def imgMock = [inside: { args, cls ->
            if (args == dockerArgs.join(" ")) {
                ran.set(true)
                cls()
            }
        }]
        def buildArgs = "-f ${dockerfile} ${dockerBuildArgs} ${dockerDir}"
        return [image: { name -> name == image ? imgMock : null },
                build: { hash, args -> args == buildArgs ? imgMock : null },
                ran  : ran]
    }
}
