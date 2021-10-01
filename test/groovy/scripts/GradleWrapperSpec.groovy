package scripts

import spock.lang.Issue
import spock.lang.Unroll
import tools.DeclarativeJenkinsSpec

class GradleWrapperSpec extends DeclarativeJenkinsSpec {
    private static final String SCRIPT_PATH = "vars/gradleWrapper.groovy"

    @Unroll
    @Issue("Tried to cover No signature of method: java.lang.Class.fromJenkins() is applicable for argument types: (gradleWrapper, java.lang.String, null) values: [gradleWrapper@7274fcad, info, null]")
    def "gradle wrapper can be called with loglevel value #logLevel and stacktrace value #stackTrace"() {
        given: "loaded gradleWrapper script"
        def gradleWrapper = loadScript(SCRIPT_PATH)

        and: "set loglevel in env/params"
        binding.setVariable("params", [LOG_LEVEL: logLevel, STACK_TRACE: stackTrace])
        binding.setVariable("env", [LOG_LEVEL: logLevel, STACK_TRACE: stackTrace])

        when: "running gradle pipeline with coverallsToken parameter"
        gradleWrapper(command)

        then:
        noExceptionThrown()

        where:
        command | logLevel | stackTrace
        "tasks" | null     | null
        "check" | "info"   | "true"
        "check" | "debug"  | "false"
    }
}
