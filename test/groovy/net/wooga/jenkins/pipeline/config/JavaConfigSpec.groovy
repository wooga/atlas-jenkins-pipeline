package net.wooga.jenkins.pipeline.config

import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

class JavaConfigSpec extends Specification {

    @Shared
    def jenkinsScript = [BUILD_NUMBER: 1, BRANCH_NAME: "branch",
                         fileExists: { String path -> false },
                         readFile  : { String path -> "" }]

    @Unroll
    def "reads java version to config"() {
        given:
        def jenkinsScript = new HashMap(jenkinsScript)
        jenkinsScript.putAll([
                fileExists: { String path -> hasVersionFile && path == ".java-version" },
                readFile  : { String path -> expectedVersion.toString() }
        ])

        when:
        def config = JavaConfig.fromConfigMap([javaVersion: javaVersion], jenkinsScript)

        then:
        config.javaHome == expectedJavaHome
        config.javaVersion == expectedVersion

        where:
        hasVersionFile | javaVersion | expectedVersion | expectedJavaHome
        false          | '23'        | 23              | "\$JAVA_23_HOME"
        false          | 23          | 23              | "\$JAVA_23_HOME"
        false          | 12          | 12              | "\$JAVA_12_HOME"
        false          | 10          | 10              | "\$JAVA_10_HOME"
        false          | null        | 11              | "\$JAVA_11_HOME"
        true           | 15          | 15              | "\$JAVA_15_HOME"
        true           | null        | 21              | "\$JAVA_21_HOME"
    }

    @Unroll
    def "creates valid config object from config map"() {
        given: "a configuration map"
        def configMap = [platforms: platforms, dockerArgs: dockerArgs, coverallsToken: cvallsToken] + extraFields
        when: "generating config object from map"
        def config = JavaConfig.fromConfigMap(configMap, jenkinsScript)
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

    JavaConfig configWith(List<String> platforms, Map dockerArgs = [:], Map gradleArgs = [:], Map extraFields = [:], String coverallsToken = null) {
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
        return new JavaConfig(baseConfig, platformObjs, 11)
    }
}
