package scripts

import com.lesfurets.jenkins.unit.MethodCall
import net.wooga.jenkins.pipeline.config.PipelineConventions
import spock.lang.Unroll
import tools.DeclarativeJenkinsSpec

import java.util.concurrent.atomic.AtomicInteger

class WDKCheckSpec extends DeclarativeJenkinsSpec {
    private static final String TEST_SCRIPT_PATH = "test/resources/scripts/checkTest.groovy"

    def setupSpec() {
        binding["BUILD_NUMBER"] = 1
    }

    def "executes cobertura coverage plugin"() {
        given: "loaded check in a running build"
        def check = loadSandboxedScript(TEST_SCRIPT_PATH)
        and: "configuration with unity platform"
        def configMap = [unityVersions: "2019"]

        and: "mocked coverage methods"
        helper.registerAllowedMethod("istanbulCoberturaAdapter", [String]) { it -> it }
        helper.registerAllowedMethod("sourceFiles", [String]) { it -> it }
        helper.registerAllowedMethod("publishCoverage", [Map]) { it -> it }
        and: "stashed setup data"
        stash["setup_w"] = [useDefaultExcludes: true, includes: "paket.lock .gradle/**, **/build/**, .paket/**, packages/**, paket-files/**, **/Paket.Unity3D/**, **/Wooga/Plugins/**"]

        when: "running gradle pipeline with coverage token"
        inSandbox {
            check.wdkCoverage(configMap, "any", "any").each { it.value() }
        }

        then: "jenkins coverage plugins are called"
        calls.has["istanbulCoberturaAdapter"] { MethodCall call ->
            call.args.length == 1 && call.args[0] == '**/codeCoverage/Cobertura.xml'
        }
        calls.has["sourceFiles"] { MethodCall call ->
            call.args.length == 1 && call.args[0] == 'STORE_LAST_BUILD'
        }
        calls.has["publishCoverage"] { MethodCall call ->
            call.args.length == 1 &&
                    call.args[0]["adapters"] == ["**/codeCoverage/Cobertura.xml"] &&
                    call.args[0]["sourceFileResolver"] == "STORE_LAST_BUILD"
        }
    }

    @Unroll("execute sonarqube when its token is present")
    def "executes sonarqube when its token is present"() {
        given: "loaded check in a running jenkins build"
        def check = loadSandboxedScript(TEST_SCRIPT_PATH)

        and: "configuration object with given platforms"
        def configMap = [unityVersions: "2019", sonarToken: sonarToken]

        and: "stashed setup data"
        stash[PipelineConventions.standard.wdkSetupStashId] = [:]

        when: "running check with sonarqube token"
        inSandbox {
            Map<String, Closure> checkSteps = check.wdkCoverage(configMap, "any", "any")
            checkSteps.each { it.value.call() }
        }

        then: "gradle coverage task is called"
        gradleCmdElements.every { it ->
            it.every { element ->
                calls.has["sh"] { MethodCall call ->
                    String args = call.args[0]["script"]
                    args.contains("gradlew") && args.contains("sonarqube") && args.contains(element)
                }
            }
        }

        where:
        name        | gradleCmdElements              | sonarToken
        "SonarQube" | [["-Dsonar.login=sonarToken"]] | "sonarToken"
    }

    def "does not executes sonarqube when its token is not present"() {
        given: "loaded check in a running jenkins build"
        def check = loadSandboxedScript(TEST_SCRIPT_PATH)

        and: "configuration object without sonarqube token"
        def config = [unityVersions: "2019"]

        and: "stashed setup data"
        stash[PipelineConventions.standard.wdkSetupStashId] = [:]

        when: "running check without sonarqube token"
        inSandbox {
            Map<String, Closure> checkSteps = check.wdkCoverage(config, "any", "any")
            checkSteps.each { it.value.call() }
        }

        then: "gradle coverage task is called"
        calls["sh"].
                collect { MethodCall call -> call.args[0]["script"] as String }.
                find { String args -> args.contains("gradlew") }.
                every { String args -> !args.contains("sonarqube") }
    }

    @Unroll("executes sonarqube on branch #branchName")
    def "executes sonarqube in PR and non-PR branches with correct arguments"() {
        given: "jenkins script object with needed properties"
        def jenkinsMeta = [BRANCH_NAME: branchName, env: [:]]
        if (isPR) {
            jenkinsMeta.env["CHANGE_ID"] = "notnull"
        }
        and: "loaded check in a running jenkins build with metadata"
        def check = loadSandboxedScript(TEST_SCRIPT_PATH) { putAll(jenkinsMeta) }

        and:
        "configuration in the ${branchName} branch with token"
        def configMap = [unityVersions: "2019", sonarToken: "sonarToken"]

        and: "stashed setup data"
        stash[PipelineConventions.standard.wdkSetupStashId] = [:]

        when: "running check with sonarqube token"
        inSandbox {
            Map<String, Closure> checkSteps = check.wdkCoverage(configMap, "any", "any")
            checkSteps.each { it.value.call() }
        }

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

    @Unroll("runs check step for unity versions #versions")
    def "runs check step for all given versions"() {
        given: "loaded check in a running jenkins build"
        def check = loadSandboxedScript(TEST_SCRIPT_PATH)
        and: "configuration object with given platforms"
        def configMap = [unityVersions: versions, wdkSetupStashId: setupStash]
        and: "stashed setup data"
        stash[setupStash] = [:]
        and: "wired checkout call"
        def checkoutDirs = []
        helper.registerAllowedMethod("checkout", [String]) {
            checkoutDirs.add(this.currentDir)
        }
        when: "running check"
        def checkSteps = inSandbox {
            Map<String, Closure> checkSteps = check.wdkCoverage(configMap, releaseType, releaseScope)
            checkSteps.each { it.value.call() }
            return checkSteps
        }

        then: "check steps names are in the `check label Unity-version(?optional)(?api)` format"
        def stepNames = checkSteps.collect { it -> it.key }
        stepNames.size() == versions.size()
        def expectedSteps = versions.collect {
            def expectedStepName = "macos Unity-${it.toString()}"
            if (it instanceof Map) {
                def versionsMap = it as Map
                expectedStepName = "${versionsMap.label ?: "macos"} Unity-${versionsMap.version}"
                if (versionsMap.optional) {
                    expectedStepName += " (optional)"
                }
                if (versionsMap.apiCompatibilityLevel) {
                    expectedStepName += " (${versionsMap.apiCompatibilityLevel})"
                }
            }
            return "check $expectedStepName".toString()
        }
        expectedSteps == stepNames

        and: "code checkouted in the right dir"
        calls["checkout"].length == versions.size()
        [configMap.unityVersions, checkoutDirs].transpose().each { versionDir ->
            versionDir[0] == versionDir[1]
        }

        and: "setup unstashed"
        calls["unstash"].count { it.args[0] == setupStash } == versions.size()

        and: "gradle check was called"
        calls["sh"].count {
            String call = it.args[0]["script"]
            return call.contains("gradlew") &&
                    call.contains("-Prelease.stage=${releaseType.trim()}") &&
                    call.contains("-Prelease.scope=${releaseScope.trim()}") &&
                    call.contains("check")
        } == versions.size()

        and: "nunit results are stored"
        calls["nunit"].count {
            Map args = it.args[0] as Map
            return args["failIfNoResults"] == false &&
                    args["testResultsPattern"] == '**/build/reports/unity/test*/*.xml'
        } == versions.size()

        where:
        versions                                                                     | releaseType | releaseScope | setupStash
        [[version: "2019"]]                                                          | "type"      | "scope"      | "setup_w"
        [[version: "2019"], [label: "linux", version: "2019.4", optional: true]]     | "type"      | "scope"      | "setup_w"
        ["2019"]                                                                     | "type"      | "scope"      | "setup_w"
        [[version: '2020.3.1f1', optional: true, apiCompatibilityLevel: 'net_standard_2_0'],
         [version: '2020.3.1f1', optional: false, apiCompatibilityLevel: 'net_4_6']] | "type"      | "scope"      | "setup_w"
        ["2019", "2020"]                                                             | "other "    | "others"     | "stash"
        ["project_version", "2020"]                                                  | "other "    | "others"     | "stash"
        [[label: "windows", version: "project_version"], "2020"]                     | "other "    | "others"     | "stash"
    }

    @Unroll("executes finally steps on check #throwsException for #versions")
    def "always executes finally steps on check run"() {
        given: "loaded check in a running jenkins build"
        def check = loadSandboxedScript(TEST_SCRIPT_PATH)
        and: "configuration object with given platforms"
        def configMap = [unityVersions: versions]
        and: "some failing script step"
        helper.registerAllowedMethod("dir", [String, Closure]) {
            if (throwsException == "throwing") throw new InputMismatchException()
        }

        when: "running check"
        inSandbox {
            Map<String, Closure> checkSteps = check.wdkCoverage(configMap, "any", "any")
            checkSteps.each {
                try {
                    it.value.call()
                } catch (InputMismatchException _) {
                } //ignores exception, it is tested in another test
            }
        }

        then: "nunit results are stored"
        calls["nunit"].count {
            Map args = it.args[0] as Map
            return args["failIfNoResults"] == false &&
                    args["testResultsPattern"] == '**/build/reports/unity/test*/*.xml'
        } == versions.size()

        where:
        versions                                    | throwsException
        ["2018"]                                    | "throwing"
        ["2018"]                                    | "not throwing"
        [[version: "2018", optional: true]]         | "throwing"
        [[version: "2018", optional: true]]         | "not throwing"
        ["2018", "2019"]                            | "throwing"
        ["2018", "2019"]                            | "not throwing"
        [[version: "2018", optional: true], "2019"] | "throwing"
        [[version: "2018", optional: true], "2019"] | "not throwing"
    }

    def "optional version does not throw Exception on failure"() {
        given: "loaded check in a running jenkins build"
        def check = loadSandboxedScript(TEST_SCRIPT_PATH)
        and: "configuration object with given platforms"
        def configMap = [unityVersions: [version]]
        and: "some failing script step"
        helper.registerAllowedMethod("dir", [String, Closure]) { throw new Exception() }

        when: "running check"
        inSandbox {
            Map<String, Closure> checkSteps = check.wdkCoverage(configMap, "any", "any")
            checkSteps.each { it.value.call() }
        }

        then:
        noExceptionThrown()
        calls.has["unstable"] { MethodCall it ->
            String message = it.args[0]["message"]
            message.startsWith("Unity build for optional version ${version.version} is found to be unstable")
        }
        where:
        version << [[version: "2019", optional: true]]
    }

    def "non-optional version throws Exception on failure"() {
        given: "loaded check in a running jenkins build"
        def check = loadSandboxedScript(TEST_SCRIPT_PATH)
        and: "configuration object with given platforms"
        def config = [unityVersions: [version]]
        and: "some failing script step"
        def expectedException = new UnknownError() //some uncommon exception type
        helper.registerAllowedMethod("dir", [String, Closure]) { throw expectedException }

        when: "running check"
        inSandbox {
            Map<String, Closure> checkSteps = check.wdkCoverage(config, "any", "any")
            checkSteps.each { it.value.call() }
        }

        then:
        def e = thrown(UnknownError)
        e == expectedException
        where:
        version << [[version: "2019"]]

    }

    @Unroll
    def "loads test environment on check"() {
        given: "loaded check in a running jenkins build"
        def check = loadSandboxedScript(TEST_SCRIPT_PATH) {}
        and: "configuration object with more than one platform"
        def configMap = [unityVersions: versions, testEnvironment: testEnvironment]

        when: "running check steps"
        Map<String, Map> checkEnvMap = versions.collectEntries { [(it): [:]] }
        Map<String, Map> analysisEnvMap = versions.collectEntries { [(it): [:]] }
        inSandbox { _ ->
            Map<String, Closure> steps = check.simpleWDK(configMap,
                    { platform ->
                        binding.env.every { checkEnvMap[platform.name][it.key] = it.value }
                    },
                    { platform ->
                        binding.env.every { analysisEnvMap[platform.name][it.key] = it.value }
                    }
            )
            steps.each { it.value.call() }
        }


        then: "test step ran for all platforms"
        checkEnvMap.every { platEnv ->
            platEnv.value["TRAVIS_JOB_NUMBER"] == "1.${platEnv.key.toUpperCase()}" &&
                    (platEnv.value["UVM_UNITY_VERSION"] == platEnv.key || platEnv.key == "project_version") &&
                    platEnv.value["UNITY_LOG_CATEGORY"] == "check-${platEnv.key}"
        }
        expectedInEnvironment.every { expPlatEnv ->
            def actualPlatEnv = checkEnvMap[expPlatEnv.key]
            actualPlatEnv.entrySet().containsAll(expPlatEnv.value.entrySet())
        }
        //analysis only runs once, so we only assert for first platform
        def firstPlat = versions[0]
        def actualAnalysisPlatEnv = analysisEnvMap[firstPlat]
        actualAnalysisPlatEnv.entrySet().containsAll(expectedInEnvironment[firstPlat].entrySet())

        where:
        versions            | testEnvironment          | expectedInEnvironment
        ["project_version"] | ["a=b", "c=d"]           | ["project_version": [a: "b", c: "d"]]
        ["2019"]            | ["a=b", "c=d"]           | ["2019": [a: "b", c: "d"]]
        ["2019"]            | ["2019": ["a=b", "c=d"]] | ["2019": [a: "b", c: "d"]]
        ["2019", "2020"]    | ["a=b", "c=d"]           | ["2019": [a: "b", c: "d"], "2020": [a: "b", c: "d"]]
        ["2019", "2020"]    | ["2020": ["a=b", "c=d"]] | ["2019": [:], "2020": [a: "b", c: "d"]]
    }

    def "applies convention to wdk check"() {
        given: "loaded check in a running jenkins build"
        def check = loadSandboxedScript(TEST_SCRIPT_PATH)
        and: "configuration object with given platforms"
        def configMap = [unityVersions    : ["any"],
                         checkTask        : convCheck,
                         sonarqubeTask    : convSonarqube,
                         wdkCoberturaFile : convWDKCoberturaFile,
                         wdkParallelPrefix: convWDKParallelPrefix,
                         wdkSetupStashId  : convWDKSetupStashId
        ]
        and: "stashed setup data"
        stash[convWDKSetupStashId] = [:]

        when: "running check"
        def checkSteps = inSandbox {
            Map<String, Closure> checkSteps = check.wdkCoverage(configMap, "releaseType", "releaseScope")
            checkSteps.each { it.value.call() }
            return checkSteps
        }

        then: "check step name has the custom prefix"
        checkSteps.collect { it -> it.key.startsWith(convWDKParallelPrefix) }

        and: "custom gradle check task was called"
        calls["sh"].count {
            String call = it.args[0]["script"]
            return call.contains("gradlew") &&
                    call.contains(convCheck)
        } == 1

        then: "custom cobertura file was set"
        calls.has["istanbulCoberturaAdapter"] { MethodCall call ->
            call.args.length == 1 && call.args[0] == convWDKCoberturaFile
        }

        where:
        convCheck  | convSonarqube | convWDKCoberturaFile | convWDKParallelPrefix | convWDKSetupStashId
        "otherchk" | "othersonar"  | "otherCobertura"     | "otherprefix"         | "otherstash"
    }

    def "wraps check and analysis step on closure wrapper"() {
        given: "loaded check in a running jenkins build"
        def check = loadSandboxedScript(TEST_SCRIPT_PATH)
        and: "counters to assert that wrappers ran"
        def testCount = new AtomicInteger(0)
        def analysisCount = new AtomicInteger(0)
        and: "configuration object with given platforms with wrapper code"
        def configMap = [unityVersions  : ["any", "other"],
                         testWrapper    : { testOp, unityPlatform ->
                             testCount.incrementAndGet()
                             testOp(unityPlatform)
                         },
                         analysisWrapper: { analysisOp, unityPlatform ->
                             analysisCount.incrementAndGet()
                             analysisOp(unityPlatform)
                         }]
        and: "stashed setup data"
        stash[PipelineConventions.standard.wdkSetupStashId] = [:]

        when: "running check"
        inSandbox {
            Map<String, Closure> checkSteps = check.wdkCoverage(configMap, "releaseType", "releaseScope")
            checkSteps.each { it.value.call() }
        }

        then: "wrapper test step ran for all platforms"
        def platformCount = configMap["unityVersions"].size()
        testCount.get() == platformCount
        and: "wrapper analysis step ran only once"
        analysisCount.get() == 1
    }

    @Unroll("checks out code on step in #checkoutDir")
    def "checks out code on step in given checkoutDir"() {
        given: "loaded check in a running jenkins build"
        def check = loadSandboxedScript(TEST_SCRIPT_PATH)
        and: "configuration object with any platforms"
        def configMap = [unityVersions: ["2019"], checkoutDir: checkoutDir]
        and: "stashed setup"
        stash[PipelineConventions.standard.wdkSetupStashId] = [:]

        when: "running check"
        def actualCheckoutDir = ""
        helper.registerAllowedMethod("checkout", [String]) {
            actualCheckoutDir = this.currentDir
        }
        inSandbox {
            Map<String, Closure> checkSteps = check.wdkCoverage(configMap, "any", "any")
            checkSteps.each { it.value.call() }
        }

        then: "steps ran on given directory"
        //checks steps + 1 analysis step
        checkoutDir == actualCheckoutDir

        where:
        checkoutDir << [".", "dir", "dir/subdir"]
    }

    @Unroll("runs test and analysis step on #checkDir")
    def "runs test and analysis step on given checkDir"() {
        given: "loaded check in a running jenkins build"
        def check = loadSandboxedScript(TEST_SCRIPT_PATH)
        and: "configuration object with any platforms and wrappers for test assertion"
        def stepsDirs = []
        def checkoutDir = "."
        def configMap = [unityVersions  : ["2019"], checkDir: checkDir,
                         checkoutDir    : checkoutDir,
                         testWrapper    : { testOp, platform ->
                             stepsDirs.add(this.currentDir)
                             testOp(platform)
                         },
                         analysisWrapper: { analysisOp, platform ->
                             stepsDirs.add(this.currentDir)
                             analysisOp(platform)
                         }]
        and: "stashed setup"
        stash["setup_w"] = [:]


        when: "running check"
        inSandbox {
            Map<String, Closure> checkSteps = check.wdkCoverage(configMap, "any", "any")
            checkSteps.each { it.value.call() }
        }

        then: "steps ran on given directory"
        //checks steps + 1 analysis step
        stepsDirs.size() == configMap.unityVersions.size() + 1
        stepsDirs.every { it == "${checkoutDir}/${checkDir}" }

        where:
        checkDir << [".", "dir", "dir/subdir"]
    }

    @Unroll("#description all check workspaces when clearWs is #clearWs")
    def "clears all check workspaces if clearWs is set"() {
        given: "loaded check in a running jenkins build"
        def check = loadSandboxedScript(TEST_SCRIPT_PATH) {}
        and: "a configuration with a mandatory platform and clearWs"
        def configMap = [unityVersions: versions, clearWs: clearWs]
        and: "stashed setup"
        stash[PipelineConventions.standard.wdkSetupStashId] = [:]

        when: "running check"
        inSandbox {
            Map<String, Closure> checkSteps = check.wdkCoverage(configMap, "any", "any")
            checkSteps.each {
                try {
                    it.value.call()
                } catch (InputMismatchException _) {
                } //ignores exception, it is tested in another test
            }
        }
        then: "all platforms workspaces are clean"
        calls["cleanWs"].length == (clearWs ? versions.size() : 0)

        where:
        versions         | clearWs
        ["2019"]         | true
        ["2019"]         | false
        ["2019", "2022"] | true
        ["2019", "2022"] | false
        description = clearWs ? "clears" : "doesn't clear"
    }
}
