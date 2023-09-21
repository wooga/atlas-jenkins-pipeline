package scripts


import spock.lang.Issue
import spock.lang.Unroll
import tools.DeclarativeJenkinsSpec

class GradleWrapperSpec extends DeclarativeJenkinsSpec {
    private static final String SCRIPT_PATH = "vars/gradleWrapper.groovy"

    @Unroll
    def "gradle wrapper is called with the environment variables #expectedEnv"() {
        given: "loaded gradleWrapper script"
        def gradleWrapper = loadSandboxedScript(SCRIPT_PATH)
        when:
        inSandbox {
            gradleWrapper command: "any", environment: givenEnv
        }

        then:
        usedEnvironments.last() == expectedEnv

        where:
        givenEnv                            | expectedEnv
        [:]                                 | [:]
        [ENV: "eager", EAGER: "eager"]      | ["ENV": "eager", "EAGER": "eager"]
        [EAGER: "eager", LAZY: { "lazy" }]  | ["EAGER": "eager", "LAZY": "lazy"]
        [ENV: { "lazy" }, LAZY: { "lazy" }] | ["ENV": "lazy", "LAZY": "lazy"]
    }

    @Unroll
    @Issue("Tried to cover No signature of method: java.lang.Class.fromJenkins() is applicable for argument types: (gradleWrapper, java.lang.String, null) values: [gradleWrapper@7274fcad, info, null]")
    def "gradle wrapper can be called with loglevel value #logLevel, stacktrace value #stackTrace and refreshDependencies value #refreshDependencies"() {
        given: "loaded gradleWrapper script"
        def gradleWrapper = loadSandboxedScript(SCRIPT_PATH)

        and: "set variables in params"
        binding.setVariable("params", [LOG_LEVEL: logLevel, STACK_TRACE: stackTrace, REFRESH_DEPENDENCIES: refreshDependencies])
        if (umask) {
            environment["UMASK"] = umask
        }

        when: "running gradle pipeline with coverallsToken parameter"
        inSandbox { gradleWrapper(command) }

        then:
        noExceptionThrown()
        calls["sh"].size() == 1
        String callString = calls["sh"].args["script"][0]
        callString.contains("gradlew")
        callString.contains(command)
        containsIf(callString, logLevel, "--${logLevel}".toString())
        containsIf(callString, umask, "umask $umask &&".toString())
        containsIf(callString, stackTrace, " --stacktrace")
        containsIf(callString, refreshDependencies, " --refresh-dependencies")

        where:
        umask  | command | logLevel | stackTrace | refreshDependencies
        null   | "tasks" | null     | null       | null
        "0123" | "tasks" | null     | null       | null
        null   | "check" | "info"   | true       | null
        null   | "check" | "debug"  | false      | null
        null   | "check" | "debug"  | false      | true
        null   | "check" | "debug"  | false      | false
        "1234" | "check" | "debug"  | false      | false
    }

    def containsIf(String callStr, def condition, String argStr) {
        return condition as Boolean ? callStr.contains(argStr) : !callStr.contains(argStr)
    }
}
