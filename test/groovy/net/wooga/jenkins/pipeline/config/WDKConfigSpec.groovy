package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.BuildVersion
import net.wooga.jenkins.pipeline.test.specifications.fakes.FakeJenkinsObject
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

class WDKConfigSpec extends Specification {

    @Shared
    def jenkinsScript = [BUILD_NUMBER: 1, BRANCH_NAME: "branch"]

    @Unroll("creates valid Unity Platform Versions object from #configMap")
    def "creates valid WDKConfig object from config map"() {
        given: "a configuration map"
        and: "a jenkins build label"
        when:
        def wdkConf = WDKConfig.fromConfigMap(configMap, jenkinsScript)
        then:
        wdkConf.unityVersions.first().unityBuildVersion.label == expected

        where:
        configMap                                                                                       | expected
        [unityVersions: [[version :"2019"]]]                                                            | 'macos'
        [unityVersions: [[version :"2019", label: "macos"]]]                                            | 'macos'
        [unityVersions: [[version :"2019", label: "linux"]]]                                            | 'linux'
    }

    @Unroll("creates valid Unity Platform Versions object from #configMap")
    def "creates valid WDKConfig object from config map"() {
        given: "a configuration map"
        and: "a jenkins build label"
        when:
        def wdkConf = WDKConfig.fromConfigMap(configMap, jenkinsScript)
        then:
        wdkConf.unityVersions.first().packageType == packageType
        wdkConf.unityVersions.first().autoref == autoref

        where:
        configMap                                                                                       | packageType  | autoref
        [unityVersions: [[version :"2019", packageType: "paket"]]]                                      | 'paket'      | true
        [unityVersions: [[version :"2019", packageType: "upm"]]]                                        | 'upm'        | true
        [unityVersions: [[version :"2019"]]]                                                            | 'any'        | true
        [unityVersions: [[version :"2019", autoref: false]]]                                            | 'any'        | false
        [unityVersions: [[version :"2019", autoref: true]]]                                             | 'any'        | true
    }

    @Unroll("creates valid Unity Platform Versions object from #configMap")
    def "creates valid WDKConfig object from config map"() {
        given: "a configuration map"
        and: "a jenkins build label"
        when:
        def wdkConf = WDKConfig.fromConfigMap(configMap, jenkinsScript)
        then:
        wdkConf.unityVersions.toSorted() == expected.unityVersions.toSorted()

        where:
        configMap                                                                                       | expected
        [unityVersions: ["2019", "2020"]]                                                               | configFor(["2019", "2020"], null, false, false, null)
        [unityVersions: [[version :"2019"], [version :"2020"]]]                                         | configFor(["2019", "2020"], null, false, false, null)
    }

    @Unroll("creates valid WDKConfig object from #configMap")
    def "creates valid WDKConfig object from config map"() {
        given: "a configuration map"
        and: "a jenkins build label"
        when:
        def wdkConf = WDKConfig.fromConfigMap(configMap, jenkinsScript)
        then:
        wdkConf.pipelineTools != null
        wdkConf.checkArgs == expected.checkArgs
        wdkConf.unityVersions.toSorted() == expected.unityVersions.toSorted()
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
        def e = thrown(Exception)
        e.message == "Please provide at least one unity version."

        where:
        unityVersions << [[], null]
    }

    @Unroll("creates valid WDKConfig from #configMap with versions for all extraLabels or none")
    def "creates valid WDKConfig object from config map with versions for all extraLabels"() {
        given: "a configuration map"
        and: "a jenkins build label"
        when:
        def wdkConf = WDKConfig.fromConfigMap(configMap, jenkinsScript, extraVesrions)
        then:
        wdkConf.unityVersions.collect {it.unityBuildVersion.label} == expected
        wdkConf.unityVersions.collect {it.platform.os} == expected

        where:
        configMap                                                                                       | extraVesrions         | expected
        [unityVersions: ["2019", "2020"]]                                                               | []                    | ["macos", "macos"]
        [unityVersions: ["2019", "2020"]]                                                               | ["macos"]             | ["macos", "macos"]
        [unityVersions: ["2019"]]                                                                       | ["linux"]             | ["macos", "linux"]
        [unityVersions: ["2019", "2020"]]                                                               | ["linux"]             | ["macos", "macos", "linux", "linux"]
        [unityVersions: ["2019"]]                                                                       | ["linux", "windows"]  | ["macos", "linux", "windows"]
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
        return unityVersionObj.withIndex().collect { Object it, int index ->
            def buildVersion = BuildVersion.parse(it)
            def platform = Platform.forWDK(buildVersion, [:], index == 0)
            return new WdkUnityBuildVersion(platform, buildVersion, "any", true)
        }
    }
}
