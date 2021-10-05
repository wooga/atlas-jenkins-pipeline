package net.wooga.jenkins.pipeline.config

import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

class ConfigSpec extends Specification {

    @Shared
    def jenkinsScript = [BUILD_NUMBER: 1, BRANCH_NAME: "branch"]

    @Unroll
    def "creates valid config object from config map"() {
        given: "a configuration map"
        def configMap = [platforms: platforms, dockerArgs: dockerArgs, coverallsToken: cvallsToken] + extraFields
        when: "generating config object from map"
        def config = Config.fromConfigMap(configMap, jenkinsScript)
        then: "generated config object is valid and matches map values"
        config.metadata == expected.metadata
        config.platforms == expected.platforms
        config.dockerArgs == expected.dockerArgs
        config.sonarArgs == expected.sonarArgs
        config.coverallsToken == expected.coverallsToken
        config.mainPlatform == expected.platforms[0]

        where:
        platforms             | dockerArgs                             | cvallsToken | extraFields             | expected
        null                  | null                                   | null        | [:]                     | configWith(["macos", "windows"])
        ["linux"]             | null                                   | null        | [:]                     | configWith(["linux"])
        ["plat", "otherplat"] | null                                   | null        | [:]                     | configWith(["plat", "otherplat"])
        ["plat", "otherplat"] | [image: "img", dockerFileName: "name"] | null        | [:]                     | configWith(["plat", "otherplat"], [image: "img", dockerFileName: "name"])
        ["plat", "otherplat"] | null                                   | "token"     | [:]                     | configWith(["plat", "otherplat"], [:], [:], "token")
        ["plat", "otherplat"] | null                                   | null        | ["sonarToken": "token"] | configWith(["plat", "otherplat"], [:], ["sonarToken": "token"], null)
        ["plat"]              | null                                   | null        | ["labels": "label"]     | configWith(["plat"], [:], ["labels": "label"], null)
    }

    Config configWith(List<String> platforms, Map dockerArgs = [:], Map extraFields = [:], String coverallsToken = null) {
        def cfgMap = [platforms: platforms, dockerArgs: dockerArgs, coverallsToken: coverallsToken] + extraFields
        new Config(JenkinsMetadata.fromScript(jenkinsScript),
                platforms.withIndex().collect { String platName, int index ->
                    Platform.forJava(platName, cfgMap, index == 0)
                },
                DockerArgs.fromConfigMap(dockerArgs),
                SonarQubeArgs.fromConfigMap(cfgMap), coverallsToken)
    }

}
