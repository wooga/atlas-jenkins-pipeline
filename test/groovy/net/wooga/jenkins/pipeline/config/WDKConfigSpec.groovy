package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.BuildVersion
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
        def wdkConf = WDKConfig.fromConfigMap(label, configMap, jenkinsScript)
        then:
        wdkConf.unityVersions == expected.unityVersions
        wdkConf.buildLabel == expected.buildLabel
        wdkConf.sonarArgs == expected.sonarArgs
        wdkConf.metadata == expected.metadata
        wdkConf.refreshDependencies == expected.refreshDependencies
        wdkConf.logLevel == expected.logLevel

        where:
        configMap                                                                                     | label | expected
        [unityVersions: ["2019", "2020"]]                                                             | "l"   | configFor(["2019", "2020"], "l", null, false, "")
        [unityVersions: ["2019"], refreshDependencies: true]                                          | "p"   | configFor(["2019"], "p", null, true, "")
        [unityVersions: ["2019"], logLevel: "level"]                                                  | "o"   | configFor(["2019"], "o", null, false, "level")
        [unityVersions: ["2019"], refreshDependencies: true, logLevel: "level"]                       | "l"   | configFor(["2019"], "l", null, true, "level")
        [unityVersions: ["2019"], refreshDependencies: false, logLevel: "level"]                      | "l"   | configFor(["2019"], "l", null, false, "level")
        [unityVersions: ["2019"], sonarToken: "token", refreshDependencies: false, logLevel: "level"] | "l"   | configFor(["2019"], "l", "token", false, "level")
    }

    @Unroll
    def "fails to create valid WDKConfig object if config map has null or empty unityVersions list"() {
        given: "a null or empty configuration map"
        when:
        WDKConfig.fromConfigMap("any", [unityVersions: unityVersions], [:])
        then:
        def e = thrown(IllegalArgumentException)
        e.message == "Please provide at least one unity version."

        where:
        unityVersions << [[], null]
    }

    def configFor(List<String> plats, String label, String sonarToken, boolean refreshDependencies, String logLevel) {
        new WDKConfig(platsFor(plats, label),
                SonarQubeArgs.fromConfigMap([sonarToken: sonarToken]),
                JenkinsMetadata.fromScript(jenkinsScript),
                refreshDependencies, logLevel, label)
    }


    def platsFor(List<?> unityVersionObj, String buildLabel) {
        return unityVersionObj.collect {
            def buildVersion = BuildVersion.parse(it)
            def platform = Platform.forWDK(buildVersion, buildLabel, [:])
            return new UnityVersionPlatform(platform, buildVersion)
        }
    }
}
