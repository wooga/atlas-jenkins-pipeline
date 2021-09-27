package net.wooga.jenkins.pipeline.config

import spock.lang.Specification
import spock.lang.Unroll

class SonarQubeArgsSpec extends Specification {
    @Unroll
    def "creates sonarqube args from config map"() {
        given: "a configuration map"
        when: "generating config object from map"
        def config = SonarQubeArgs.fromConfigMap(configMap)

        then: "generated config object is valid and matches map values"
        config == expected

        where:
        configMap                             | expected
        [sonarToken: null]                    | new SonarQubeArgs(null)
        [sonarToken: "token"]                 | new SonarQubeArgs("token")
        [other: "other", sonarToken: "token"] | new SonarQubeArgs("token")
    }

    def "indicates to run sonarqube if token is present"() {
        given: "a valid sonarqube args object"
        def args = new SonarQubeArgs(token)

        when: "checking if sonarqube should run"
        def shouldRun = args.shouldRunSonarQube()

        then:
        shouldRun == runs

        where:
        token   | runs
        "token" | true
        null    | false
    }

}
