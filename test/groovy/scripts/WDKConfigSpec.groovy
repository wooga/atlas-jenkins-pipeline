package scripts

import net.wooga.jenkins.pipeline.BuildVersion
import net.wooga.jenkins.pipeline.config.BaseConfig
import net.wooga.jenkins.pipeline.config.CheckArgs
import net.wooga.jenkins.pipeline.config.DockerArgs
import net.wooga.jenkins.pipeline.config.GradleArgs
import net.wooga.jenkins.pipeline.config.Config
import net.wooga.jenkins.pipeline.config.JenkinsMetadata
import net.wooga.jenkins.pipeline.config.PipelineConventions
import net.wooga.jenkins.pipeline.config.Platform
import spock.lang.Shared
import spock.lang.Unroll
import tools.DeclarativeJenkinsSpec
import tools.FakeJenkinsObject

class WDKConfigSpec extends DeclarativeJenkinsSpec {

    @Shared
    def jenkinsScript = new FakeJenkinsObject([BUILD_NUMBER: 1, BRANCH_NAME: "branch"])


    @Unroll("creates valid WDK Config object from #configMap")
    def "creates valid WDK Config object from config map"() {
        given: "a configuration map"
        and: "a jenkins build label"
        and: "loaded configs script"
        def configs = loadSandboxedScript("vars/configs.groovy")
        when:
        def wdkConf = inSandboxClonedReturn { configs().unityWDK(label, configMap, jenkinsScript)}  as Config
        then:
        wdkConf.pipelineTools != null
        wdkConf.checkArgs == expected.checkArgs
        wdkConf.platforms == expected.platforms
        wdkConf.gradleArgs == expected.gradleArgs
        wdkConf.metadata == expected.metadata

        where:
        configMap                                                                                      | label | expected
        [unityVersions: ["2019", "2020"]]                                                              | "l"   | configFor(["2019", "2020"], "l", null, false, false, null)
        [unityVersions: ["2019"], refreshDependencies: true]                                           | "p"   | configFor(["2019"], "p", null, true, false, null)
        [unityVersions: ["2019"], logLevel: "level"]                                                   | "o"   | configFor(["2019"], "o", null, false, false, "level")
        [unityVersions: ["2019"], refreshDependencies: true, logLevel: "level"]                        | "l"   | configFor(["2019"], "l", null, true, false, "level")
        [unityVersions: ["2019"], refreshDependencies: false, logLevel: "level"]                       | "l"   | configFor(["2019"], "l", null, false, false, "level")
        [unityVersions: ["2019"], refreshDependencies: false, showStackTrace: true, logLevel: "level"] | "l"   | configFor(["2019"], "l", null, false, true, "level")
        [unityVersions: ["2019"], sonarToken: "token", refreshDependencies: false, logLevel: "level"]  | "l"   | configFor(["2019"], "l", "token", false, false, "level")
    }

    @Unroll
    def "fails to create valid WDK Config object if config map has null or empty unityVersions list"() {
        given: "a null or empty configuration map"
        and: "loaded configs script"
        def configs = loadSandboxedScript("vars/configs.groovy")
        when:
        inSandbox { configs().unityWDK("any", [unityVersions: unityVersions], new FakeJenkinsObject([:])) }
        then:
        def e = thrown(Exception)
        e.message == "Please provide at least one unity version."

        where:
        unityVersions << [[], null]
    }

    def configFor(List<String> plats, String label, String sonarToken, boolean refreshDependencies, boolean showStackTrace, String logLevel) {
        def metadata = JenkinsMetadata.fromScript(jenkinsScript)
        def platforms = platsFor(plats, label)
        def baseConfig = new BaseConfig(jenkinsScript,
                PipelineConventions.standard.mergeWithConfigMap([:]),
                metadata,
                GradleArgs.fromConfigMap([refreshDependencies: refreshDependencies, showStackTrace: showStackTrace, logLevel: logLevel]),
                DockerArgs.fromConfigMap([:]),
                CheckArgs.fromConfigMap(jenkinsScript, metadata, [sonarToken: sonarToken]))
        return new Config(baseConfig, platforms)
    }


    def platsFor(List<?> unityVersionObj, String buildLabel) {
        return unityVersionObj.withIndex().collect { Object it, int index ->
            def buildVersion = BuildVersion.parse(it)
            Platform.forWDK(buildVersion, buildLabel, [:], index == 0)
        }
    }
}
