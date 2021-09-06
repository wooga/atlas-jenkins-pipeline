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
        configMap                                                                 | expected
        [sonarToken: null, sonarQubeBranchPattern: null]                          | new SonarQubeArgs(null, "^(master|main)\$")
        [sonarToken: "token", sonarQubeBranchPattern: null]                       | new SonarQubeArgs("token", "^(master|main)\$")
        [sonarToken: null, sonarQubeBranchPattern: "^pattern"]                    | new SonarQubeArgs(null, "^pattern")
        [sonarToken: "token", sonarQubeBranchPattern: "^pattern"]                 | new SonarQubeArgs("token", "^pattern")
        [other: "other", sonarToken: "token", sonarQubeBranchPattern: "^pattern"] | new SonarQubeArgs("token", "^pattern")
    }

    def "indicates to run sonarqube if token is present and branch matches pattern"() {
        given: "a valid sonarqube args object"
        def args = new SonarQubeArgs(token, pattern)

        when: "checking if sonarqube should run"
        def shouldRun = args.shouldRunSonarQube(branch)

        then:
        shouldRun == runs

        where:
        token   | branch      | pattern     | runs
        "token" | "master"    | "^master\$" | true
        "token" | "notmaster" | "^master\$" | false
        null    | "master"    | "^master\$" | false
        null    | "notmaster" | "^master\$" | false
    }

}
