package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.model.EnvVars
import spock.lang.Specification
import spock.lang.Unroll

class GradleArgsSpec extends Specification {

    @Unroll
    def "creates gradle args from config map"() {
        given: "a gradle args configuration map field"
        def gradleArgs = [
                gradleEnvironment  : env,
                logLevel           : logLevel,
                showStackTrace     : stackTrace,
                refreshDependencies: refreshDeps]

        when: "generating config object from map"
        def config = GradleArgs.fromConfigMap(gradleArgs)

        then: "generated config object is valid and matches map values"
        config == expected
        env.keySet().forEach {key ->
            assert config.environment.environment[key].call() == env[key]
        }

        where:
        env            | logLevel | stackTrace | refreshDeps | expected
        [:]            | null     | null       | null        | new GradleArgs(new EnvVars([:]), null, false, false)
        [:]            | "info"   | false      | false       | new GradleArgs(new EnvVars([:]), "info", false, false)
        [:]            | "quiet"  | true       | false       | new GradleArgs(new EnvVars([:]), "quiet", true, false)
        [:]            | "quiet"  | false      | true        | new GradleArgs(new EnvVars([:]), "quiet", false, true)
        [:]            | "quiet"  | false      | true        | new GradleArgs(new EnvVars([:]), "quiet", false, true)
        [:]            | "quiet"  | false      | true        | new GradleArgs(new EnvVars([:]), "quiet", false, true)
        [env: "value"] | "quiet"  | false      | true        | new GradleArgs(new EnvVars([env: "value"]), "quiet", false, true)
    }

}
