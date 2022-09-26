package scripts


import spock.lang.Issue
import spock.lang.Unroll
import tools.DeclarativeJenkinsSpec

class GradleWrapperSpec extends DeclarativeJenkinsSpec {
    private static final String SCRIPT_PATH = "vars/gradleWrapper.groovy"

    @Unroll
    @Issue("Tried to cover No signature of method: java.lang.Class.fromJenkins() is applicable for argument types: (gradleWrapper, java.lang.String, null) values: [gradleWrapper@7274fcad, info, null]")
    def "gradle wrapper can be called with loglevel value #logLevel, stacktrace value #stackTrace and refreshDependencies value #refreshDependencies"() {
        given: "loaded gradleWrapper script"
        def gradleWrapper = loadSandboxedScript(SCRIPT_PATH)

        and: "set variables in params"
        binding.setVariable("params", [LOG_LEVEL: logLevel, STACK_TRACE: stackTrace, REFRESH_DEPENDENCIES: refreshDependencies])

        when: "running gradle pipeline with coverallsToken parameter"
        inSandbox { gradleWrapper(command) }

        then:
        noExceptionThrown()
        calls["sh"].size() == 1
        String callString = calls["sh"].args["script"][0]
        callString.contains("gradlew")
        callString.contains(command)
        containsArgIf(callString, logLevel, "--${logLevel}".toString())
        containsArgIf(callString, stackTrace, " --stacktrace")
        containsArgIf(callString, refreshDependencies, " --refresh-dependencies")

        where:
        command | logLevel | stackTrace | refreshDependencies
        "tasks" | null     | null       | null
        "check" | "info"   | true       | null
        "check" | "debug"  | false      | null
        "check" | "debug"  | false      | true
        "check" | "debug"  | false      | false
    }

    def containsArgIf(String callStr, def condition, String argStr) {
        return condition as Boolean ? callStr.contains(argStr) : !callStr.contains(argStr)
    }
}
