package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.BuildVersion
import net.wooga.jenkins.pipeline.test.specifications.fakes.FakeJenkinsObject
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

class WDKConfigSpec extends Specification {

    @Shared
    def jenkinsScript = [BUILD_NUMBER: 1, BRANCH_NAME: "branch"]


    @Unroll("creates valid WDKConfig object from #configMap")
    def "creates valid WDKConfig object from config map"() {
        given: "a configuration map"
        and: "a jenkins build label"
        when:
        def wdkConf = WDKConfig.fromConfigMap(configMap, jenkinsScript)
        then:
        wdkConf.pipelineTools != null
        wdkConf.checkArgs == expected.checkArgs
        wdkConf.unityVersions == expected.unityVersions
        wdkConf.gradleArgs == expected.gradleArgs
        wdkConf.metadata == expected.metadata

        where:
        configMap                                                                                       | expected
        [unityVersions: ["2019", "2020"]]                                                               | configFor(["2019", "2020"], null, false, false, null)
        [unityVersions: ["2019"], refreshDependencies: true]                                            | configFor(["2019"], null, true, false, null)
        [unityVersions: ["2019"], logLevel: "level"]                                                    | configFor(["2019"], null, false, false, "level")
        [unityVersions: ["2019"], refreshDependencies: true, logLevel: "level"]                         | configFor(["2019"], null, true, false, "level")
        [unityVersions: ["2019"], refreshDependencies: false, logLevel: "level"]                        | configFor(["2019"], null, false, false, "level")
        [unityVersions: ["2019"], refreshDependencies: false, showStackTrace: true, logLevel: "level"]  | configFor(["2019"], null, false, true, "level")
        [unityVersions: ["2019"], sonarToken: "token", refreshDependencies: false, logLevel: "level"]   | configFor(["2019"], "token", false, false, "level")
    }

    @Unroll
    def "fails to create valid WDKConfig object if config map has null or empty unityVersions list"() {
        given: "a null or empty configuration map"
        when:
        WDKConfig.fromConfigMap([unityVersions: unityVersions], new FakeJenkinsObject([:]))
        then:
        def e = thrown(IllegalArgumentException)
        e.message == "Please provide at least one unity version."

        where:
        unityVersions << [[], null]
    }

    def configFor(List<String> unityVersionObj, String sonarToken, boolean refreshDependencies, boolean showStackTrace, String logLevel) {
        def metadata = JenkinsMetadata.fromScript(jenkinsScript)
        def unityVersions = platsFor(unityVersionObj)
        def baseConfig = new BaseConfig(jenkinsScript,
                PipelineConventions.standard.mergeWithConfigMap([:]),
                metadata,
                GradleArgs.fromConfigMap([refreshDependencies: refreshDependencies, showStackTrace: showStackTrace, logLevel: logLevel]),
                DockerArgs.fromConfigMap([:]),
                CheckArgs.fromConfigMap(jenkinsScript, metadata, [sonarToken: sonarToken]))
        return new WDKConfig(unityVersions, baseConfig)
    }


    def platsFor(List<?> unityVersionObj) {
        return unityVersionObj.withIndex().collectMany { Object it, int index ->
            def buildVersion = BuildVersion.parse(it)
            def linuxBuildVersion = buildVersion.copy([label: "linux", optional: true])

            def platform = Platform.forWDK(buildVersion, [:], index == 0)
            def linuxPlatform = Platform.forWDK(linuxBuildVersion, [:], false)
            return [new UnityVersionPlatform(platform, buildVersion),
                    new UnityVersionPlatform(linuxPlatform, linuxBuildVersion)]
        }
    }
}
