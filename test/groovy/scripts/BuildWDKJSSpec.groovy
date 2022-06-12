package scripts

import com.lesfurets.jenkins.unit.MethodCall
import net.wooga.jenkins.pipeline.config.PipelineConventions
import spock.lang.Unroll
import tools.DeclarativeJenkinsSpec

import java.util.concurrent.atomic.AtomicInteger

class BuildWDKJSSpec extends DeclarativeJenkinsSpec {

    private final String SCRIPT_PATH = "vars/buildWDKJS.groovy"

    @Unroll("execute #name if their token(s) are present")
    def "execute coverage when its token is present"() {

        given: "configuration in the master branch and with tokens"
        def configMap = [sonarToken: sonarToken, coverallsToken: coverallsToken]
        def jenkinsMeta = [BRANCH_NAME: "master"]

        and: "loaded check in a running build"
        def buildWDKJS = loadSandboxedScript(SCRIPT_PATH) { it -> it.putAll(jenkinsMeta) }

        when: "running gradle pipeline with coverage token"
        inSandbox {
            //class loaders are ~~hell~~ FUN
            buildWDKJS(configMap)
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

        given: "jenkins script object with needed properties"
        def jenkinsMeta = [BRANCH_NAME: branchName, env: [:]]
        if (isPR) {
            jenkinsMeta.CHANGE_ID = "notnull"
            jenkinsMeta.env["CHANGE_ID"] = "notnull"
        }

        and: "loaded build script in a running build in branch"
        def buildWDKJS = loadSandboxedScript(SCRIPT_PATH) { it -> it.putAll(jenkinsMeta) }

        and:
        "configuration in the ${branchName} branch with token"
        def configMap = [sonarToken: "sonarToken"]

        when: "running gradle pipeline with sonar token"
        inSandbox { buildWDKJS(configMap) }

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
        def buildWDKJS = loadSandboxedScript(SCRIPT_PATH)
        and: "configuration object with given platforms"
        def configMap = [platforms: platforms]

        when: "running check"
        inSandbox { buildWDKJS(configMap) }

        then:
        calls["checkout"].length == platforms.size()
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
        Fixtures.createTmpFile(dockerDir, dockerfile)
        and: "a mocked jenkins docker object"
        def dockerMock = Fixtures.createDockerFake(dockerfile, image, dockerDir, dockerBuildArgs, dockerArgs)
        def buildWDKJS = loadSandboxedScript(SCRIPT_PATH) {
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
            buildWDKJS(configMap)
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

    def "applies convention to check"() {
        given: "loaded check in a running jenkins build"
        def buildWDKJS = loadSandboxedScript(SCRIPT_PATH)
        and: "configuration object with any platforms"
        def configMap = [platforms: ["linux"],
                         sonarToken: "token", coverallsToken: "token",
                         checkTask: convCheck, sonarqubeTask: convSonarqubeTask,
                         jacocoTask: convJacocoTask, javaParallelPrefix: convJavaParallelPrefix,
                         coverallsTask: convCoverallsTask]

        when: "running check"
        inSandbox {
            buildWDKJS(configMap)
        }

        then:
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
        where:
        convCheck  | convSonarqubeTask | convCoverallsTask | convJacocoTask | convJavaParallelPrefix
        "otherchk" | "othersq"         | "othercv"         | "otherjc"      | "othercheck"
    }

    def "wraps test and analysis step on closure wrapper"() {
        given: "loaded check in a running jenkins build"
        def buildWDKJS = loadSandboxedScript(SCRIPT_PATH)
        and: "wrapper validation counters"
        def testCount = new AtomicInteger(0)
        def analysisCount = new AtomicInteger(0)
        and: "configuration object with any platforms and desired wrappers"

        def configMap = [
                platforms: ["linux", "windows"],
                sonarToken: "token", coverallsToken: "token",
                testWrapper: { testOp, platform ->
                    testCount.incrementAndGet()
                    testOp(platform)
                },
                analysisWrapper: { analysisOp, platform ->
                    analysisCount.incrementAndGet()
                    analysisOp(platform)
                }
        ]

        when: "running check"
        inSandbox {
            buildWDKJS(configMap)
        }

        then: "wrapper test step ran for all platforms"
        def platformCount = configMap["platforms"].size()
        testCount.get() == platformCount
        and: "inner test step ran for all platforms"
        calls["sh"].count {
            def argsString = it.argsToString()
            argsString.contains("gradlew") && argsString.contains(PipelineConventions.standard.checkTask)
        } == platformCount
        and: "wrapper analysis step ran only once"
        analysisCount.get() == 1
        and: "inner analysis step ran only once"
        calls["sh"].count {
            def argsString = it.argsToString()
            argsString.contains("gradlew") && argsString.contains(PipelineConventions.standard.sonarqubeTask)
        } == 1
    }

    @Unroll("checks out step in #checkoutDir")
    def "checks out step on given checkoutDir"() {
        given: "loaded check in a running jenkins build"
        def buildWDKJS = loadSandboxedScript(SCRIPT_PATH)
        and: "configuration object with any platforms"
        def configMap = [platforms: ["linux"], checkoutDir: checkoutDir]
        and: "wired checkout operation"
        def actualCheckoutDir = ""
        helper.registerAllowedMethod("checkout", [String]) {
            actualCheckoutDir = this.currentDir
        }

        when: "running check"
        inSandbox {
            buildWDKJS(configMap)
        }

        then: "checkout ran in given directory"
        checkoutDir == actualCheckoutDir

        where:
        checkoutDir << [".", "dir", "dir/subdir"]
    }

    @Unroll("runs test and analysis step on #checkDir")
    def "runs test and analysis step on given checkDir"() {
        given: "loaded check in a running jenkins build"
        def buildWDKJS = loadSandboxedScript(SCRIPT_PATH)
        and: "configuration object with any platforms and wrappers for test result capture"
        def stepsDirs = []
        def checkoutDir = "."
        def configMap = [platforms: ["linux"], checkDir: checkDir,
                         checkoutDir: checkoutDir,
                         testWrapper: { testOp, platform ->
                             stepsDirs.add(this.currentDir)
                             testOp(platform)
                         },
                         analysisWrapper: { analysisOp, platform ->
                             stepsDirs.add(this.currentDir)
                             analysisOp(platform)
                         }]

        when: "running check"
        inSandbox {
            Map<String, Closure> checkSteps = buildWDKJS(configMap)
            checkSteps.each { it.value.call() }
        }

        then: "steps ran on given directory"
        //checks steps + 1 analysis step
        stepsDirs.size() == configMap.platforms.size() + 1
        stepsDirs.every {it == "${checkoutDir}/${checkDir}"}

        where:
        checkDir << [".", "dir", "dir/subdir"]
    }

    @Unroll("#description all check workspaces when clearWs is #clearWs")
    def "clears all check workspaces if clearWs is set"() {
        given: "loaded check in a running jenkins build"
        def buildWDKJS = loadSandboxedScript(SCRIPT_PATH) {}

        when: "running check"
        inSandbox {
            Map<String, Closure> checkSteps = buildWDKJS([platforms: platforms, clearWs: clearWs])
            checkSteps.each { it.value.call() }
        }

        then: "all platforms workspaces are clean"
        calls["cleanWs"].length == (clearWs? platforms.size() : 0)

        where:
        platforms | clearWs
        ["linux"] | true
        ["linux"] | false
        ["linux", "windows"] | true
        ["linux", "windows"] | false
        description = clearWs? "clears" : "doesn't clear"
    }
//    jenkins.usernamePassword(credentialsId: npmCredsSecret,
//    usernameVariable:"NODE_RELEASE_NPM_USER",
//    passwordVariable: "NODE_RELEASE_NPM_PASS")
    @Unroll("publishes #releaseType-#releaseScope release ")
    def "publishes with #release release type"() {
        given: "credentials holder with bintray publish keys"
        credentials.addUsernamePassword('atlas_npm_credentials', "npmusr", "npmpwd")
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
        skipsRelease ^ gradleCall.contains(releaseType)
        skipsRelease ^ gradleCall.contains("-Prelease.stage=${releaseType}")
        skipsRelease ^ gradleCall.contains("-Prelease.scope=${releaseScope}")
        skipsRelease ^ gradleCall.contains("-x check")
        and: "sets credentials on environment"
        def env =  usedEnvironments.last()
        skipsRelease ^ env["NODE_RELEASE_NPM_USER"] == "npmusr"
        skipsRelease ^ env["NODE_RELEASE_NPM_PASS"] == "npmpwd"

        where:
        releaseType | releaseScope | skipsRelease
        "snapshot"  | "patch"      | true
        "rc"        | "minor"      | false
        "final"     | "major"      | false
    }

    def "registers environment on publish"() {
        given: "credentials holder with bintray publish keys"
        credentials.addUsernamePassword('atlas_npm_credentials', "npmusr", "npmpwd")
        credentials.addUsernamePassword('github_access', "gitusr", "gitpwd")
        and: "build plugin with publish parameters"
        def buildWDKJS = loadSandboxedScript(SCRIPT_PATH) {
            currentBuild["result"] = null
            params.RELEASE_TYPE = "not-snapshot"
            params.RELEASE_SCOPE = "any"
            env.GRGIT_USR = "gitusr"
            env.GRGIT_PSW = "gitpwd"
        }

        when: "running buildJavaLibrary pipeline"
        inSandbox { buildWDKJS() }

        then: "sets up GRGIT environment"
        def env = buildWDKJS.binding.env
        env["GRGIT"] == credentials['github_access']
        env["GRGIT_USER"] == "gitusr" //"${GRGIT_USR}"
        env["GRGIT_PASS"] == "gitpwd" //"${GRGIT_PSW}"
        and: "sets up github environment"
        env["GITHUB_LOGIN"] == "gitusr" //"${GRGIT_USR}"
        env["GITHUB_PASSWORD"] == "gitpwd" //"${GRGIT_PSW}"
    }

    @Unroll
    def "publish step is executed on #mainPlatform label"() {
        given: "pipeline loaded with publish parameters"
        def buildWDKJS = loadSandboxedScript(SCRIPT_PATH) {
            params.RELEASE_TYPE = "not-snapshot"
            params.RELEASE_SCOPE = "any"
        }

        when: "running pipeline"
        inSandbox { buildWDKJS(platforms: platforms) }

        then: "publish step is executed with expected label"
        pipeline.stages["publish"].agent.label == "$mainPlatform && atlas"

        where:
        platforms << [["macos", "windows"], ["linux"]]
        mainPlatform = platforms?.get(0)
    }

}
