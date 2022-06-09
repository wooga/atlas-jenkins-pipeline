package scripts

import net.wooga.jenkins.pipeline.config.BaseConfig
import net.wooga.jenkins.pipeline.config.CheckArgs
import net.wooga.jenkins.pipeline.config.Config
import net.wooga.jenkins.pipeline.config.DockerArgs
import net.wooga.jenkins.pipeline.config.GradleArgs
import net.wooga.jenkins.pipeline.config.JenkinsMetadata
import net.wooga.jenkins.pipeline.config.PipelineConventions
import net.wooga.jenkins.pipeline.config.Platform
import spock.lang.Shared
import spock.lang.Unroll
import tools.DeclarativeJenkinsSpec

class JavaConfigSpec extends DeclarativeJenkinsSpec {

    @Shared
    def jenkinsScript = [BUILD_NUMBER: 1, BRANCH_NAME: "branch"]

    @Unroll
    def "creates valid config object from config map"() {
        given: "a configuration map"
        def configMap = [platforms: platforms, dockerArgs: dockerArgs, coverallsToken: cvallsToken] + extraFields
        and: "loaded configs script"
        def configs = loadSandboxedScript("vars/configs.groovy")
        when: "generating config object from map"
        def config = inSandboxClonedReturn { configs().java(configMap, jenkinsScript) } as Config
        then: "generated config object is valid and matches map values"
        config.metadata == expected.metadata
        config.platforms == expected.platforms
        config.checkArgs == expected.checkArgs
        config.dockerArgs == expected.dockerArgs
        config.mainPlatform == expected.platforms[0]

        where:
        platforms             | dockerArgs                             | gradleArgs                                                      | cvallsToken | extraFields             | expected
        null                  | null                                   | null                                                            | null        | [:]                     | configWith(["macos", "windows"])
        ["linux"]             | null                                   | null                                                            | null        | [:]                     | configWith(["linux"])
        ["plat", "otherplat"] | null                                   | null                                                            | null        | [:]                     | configWith(["plat", "otherplat"])
        ["plat", "otherplat"] | [image: "img", dockerFileName: "name"] | null                                                            | null        | [:]                     | configWith(["plat", "otherplat"], [image: "img", dockerFileName: "name"])
        ["plat", "otherplat"] | null                                   | null                                                            | "token"     | [:]                     | configWith(["plat", "otherplat"], [:], [:], [:], "token")
        ["plat", "otherplat"] | null                                   | null                                                            | null        | ["sonarToken": "token"] | configWith(["plat", "otherplat"], [:], [:], ["sonarToken": "token"], null)
        ["plat"]              | null                                   | null                                                            | null        | ["labels": "label"]     | configWith(["plat"], [:], [:], ["labels": "label"], null)
        ["plat"]              | null                                   | [logLevel: "info", stackTrace: true, refreshDependencies: true] | null        | ["labels": "label"]     | configWith(["plat"], [:], [logLevel: "info", stackTrace: true, refreshDependencies: true], ["labels": "label"], null)
    }

    Config configWith(List<String> platforms, Map dockerArgs = [:], Map gradleArgs = [:], Map extraFields = [:], String coverallsToken = null) {
        def cfgMap = [platforms: platforms, dockerArgs: dockerArgs, coverallsToken: coverallsToken] + extraFields
        def metadata = JenkinsMetadata.fromScript(jenkinsScript)
        def baseConfig = new BaseConfig(jenkinsScript,
                PipelineConventions.standard.mergeWithConfigMap(cfgMap),
                metadata,
                GradleArgs.fromConfigMap(gradleArgs),
                DockerArgs.fromConfigMap(dockerArgs),
                CheckArgs.fromConfigMap(jenkinsScript, metadata, cfgMap))
        def platformObjs = platforms.withIndex().collect { String platName, int index ->
            Platform.forJava(platName, cfgMap, index == 0)
        }
        return new Config(baseConfig, platformObjs)
    }
}
