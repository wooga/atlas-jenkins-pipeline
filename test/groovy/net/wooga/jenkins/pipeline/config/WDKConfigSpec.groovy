package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.BuildVersion
import spock.lang.Specification
import spock.lang.Unroll

class WDKConfigSpec extends Specification {

    @Unroll("creates valid WDKConfig object from #configMap")
    def "creates valid WDKConfig object from config map"() {
        given: "a configuration map"
        and: "a jenkins build label"
        when:
        def wdkConf = WDKConfig.fromConfigMap(label, configMap)
        then:
        wdkConf.unityVersions == expected.unityVersions
        wdkConf.buildLabel == expected.buildLabel
        wdkConf.refreshDependencies == expected.refreshDependencies
        wdkConf.logLevel == expected.logLevel

        where:
        configMap                                                                | label | expected
        [unityVersions: ["2019", "2020"]]                                        | "l"   | new WDKConfig(platsFor(["2019", "2020"], "l"), false, "", "l")
        [unityVersions: ["2019"], refreshDependencies: true]                     | "p"   | new WDKConfig(platsFor(["2019"], "p"), true, "", "p")
        [unityVersions: ["2019"], logLevel: "level"]                             | "o"   | new WDKConfig(platsFor(["2019"], "o"), false, "level", "o")
        [unityVersions: ["2019"], refreshDependencies: true, logLevel: "level"]  | "l"   | new WDKConfig(platsFor(["2019"], "l"), true, "level", "l")
        [unityVersions: ["2019"], refreshDependencies: false, logLevel: "level"] | "l"   | new WDKConfig(platsFor(["2019"], "l"), false, "level", "l")
    }

    @Unroll
    def "fails to create valid WDKConfig object if config map has null or empty unityVersions list"() {
        given: "a null or empty configuration map"
        when:
        WDKConfig.fromConfigMap("any", [unityVersions: unityVersions])
        then:
        def e = thrown(IllegalArgumentException)
        e.message == "Please provide at least one unity version."

        where:
        unityVersions << [[], null]
    }


    def platsFor(List<?> unityVersionObj, String buildLabel) {
        return unityVersionObj.collect {
            def buildVersion = BuildVersion.parse(it)
            def platform = Platform.forWDK(buildVersion, buildLabel, [:])
            return new UnityVersionPlatform(platform, buildVersion)
        }
    }
}
