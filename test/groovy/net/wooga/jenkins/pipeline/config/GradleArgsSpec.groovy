package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.model.EnvVars
import spock.lang.Specification
import spock.lang.Unroll

class GradleArgsSpec extends Specification {

    @Unroll
    def "creates gradle args from config map"() {
        given: "a gradle args configuration map field"
        def gradleArgs = [logLevel: logLevel, showStackTrace: stackTrace, refreshDependencies: refreshDeps]

        when: "generating config object from map"
        def config = GradleArgs.fromConfigMap(gradleArgs)

        then: "generated config object is valid and matches map values"
        config == expected

        where:
        logLevel | stackTrace | refreshDeps | expected
        null     | null       | null        | new GradleArgs(EnvVars.fromList([]), null, false, false)
        "info"   | false      | false       | new GradleArgs(EnvVars.fromList([]), "info", false, false)
        "quiet"  | true       | false       | new GradleArgs(EnvVars.fromList([]), "quiet", true, false)
        "quiet"  | false      | true        | new GradleArgs(EnvVars.fromList([]), "quiet", false, true)
    }

}
