package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.BuildVersion
import spock.lang.Specification
import spock.lang.Unroll

class PlatformSpec extends Specification {

    @Unroll("creates platform named #platName from java config map #config")
    def "creates platform from java config map"() {
        given: "a configuration map"
        and: "a platform name"

        when: "generating new platform"
        def platform = Platform.forJava(platName, config, isMain)

        then: "generated platform is valid and matches map values"
        platform.checkDirectory == expected.checkDirectory
        platform.name == expected.name
        platform.os == expected.os
        platform.os == platform.name
        platform.runsOnDocker == platform.runsOnDocker
        platform.labels == expected.labels
        platform.testEnvironment == expected.testEnvironment
        platform.testLabels == expected.testLabels

        where:
        config                                                                            | isMain | platName | expected
        [labels: null, testEnvironment: null, testLabels: null, dockerArgs: null]         | true   | "name"   | new Platform(".", ".", "name", "name", false, "", [], [], true, false)
        [checkDir: "dir"]                                                                 | false  | "name"   | new Platform(".", "dir", "name", "name", false, "", [], [], false, false)
        [checkoutDir: "dir"]                                                              | false  | "name"   | new Platform("dir", ".", "name", "name", false, "", [], [], false, false)
        [checkoutDir: "dir", clearWs: true]                                               | false  | "name"   | new Platform("dir", ".", "name", "name", false, "", [], [], false, true)
        [clearWs: true]                                                                   | false  | "name"   | new Platform("dir", ".", "name", "name", false, "", [], [], false, true)
        [testEnvironment: ["t=a", "t2=b"]]                                                | false  | "name"   | new Platform(".", ".", "name", "name", false, "", ["t=a", "t2=b"], [], false, false)
        [testEnvironment: ["t=a", "t2=b"], testLabels: ["l", "l2"]]                       | true   | "name"   | new Platform(".", ".", "name", "name", false, "", ["t=a", "t2=b"], ["l", "l2"], true, false)
        [labels: "label", testLabels: ["t", "t2"]]                                        | false  | "name"   | new Platform(".", ".", "name", "name", false, "label", [], ["t", "t2"], false, false)
        [labels: "label", testLabels: [name: ["t", "t2"]]]                                | false  | "name"   | new Platform(".", ".", "name", "name", false, "label", [], ["t", "t2"], false, false)
        [labels: "label", testLabels: [notname: ["t", "t2"]]]                             | false  | "name"   | new Platform(".", ".", "name", "name", false, "label", [], [], false, false)
        [labels: "label", testEnvironment: [name: ["t=a", "t2=b"]]]                       | false  | "name"   | new Platform(".", ".", "name", "name", false, "label", ["t=a", "t2=b"], [], false, false)
        [labels: "label", testEnvironment: [notname: ["t=a", "t2=b"]]]                    | false  | "name"   | new Platform(".", ".", "name", "name", false, "label", [], [], false, false)
        [labels: "label", testEnvironment: ["t=a", "t2=b"]]                               | false  | "name"   | new Platform(".", ".", "name", "name", false, "label", ["t=a", "t2=b"], [], false, false)
        [labels: "label", testEnvironment: ["t=a", "t2=b"], testLabels: ["l", "l2"]]      | false  | "name"   | new Platform(".", ".", "name", "name", false, "label", ["t=a", "t2=b"], ["l", "l2"], false, false)
        [labels: null, testEnvironment: null, testLabels: null, dockerArgs: [not: "nul"]] | false  | "name"   | new Platform(".", ".", "name", "name", false, "", [], [], false, false)
        [labels: null, testEnvironment: null, testLabels: null, dockerArgs: [not: "nul"]] | false  | "linux"  | new Platform(".", ".", "linux", "linux", true, "", [], [], false, false)
    }


    @Unroll("creates platform from wdk config map #config")
    def "creates platform from wdk config map"() {
        given: "a configuration map for wdk"
        and: "a unity build version"

        when: "generating new platform"
        def platform = Platform.forWDK(buildVersion, config, isMain)

        then: "generated platform is valid, matches map values and contains unity environment"
        def unityEnv = ["UNITY_LOG_CATEGORY=check-${buildVersion.version}", "UVM_UNITY_VERSION=${buildVersion.version}"].with {
            it.addAll(buildVersion.apiCompatibilityLevel ? ["UNITY_API_COMPATIBILITY_LEVEL=${buildVersion.apiCompatibilityLevel}"] : [])
            return it
        }
        platform.checkoutDirectory == expected.checkoutDirectory
        platform.checkDirectory == expected.checkDirectory
        platform.name == expected.name
        platform.os == buildVersion.label
        !platform.runsOnDocker
        platform.labels == expected.labels
        platform.testEnvironment == expected.testEnvironment + unityEnv
        platform.testLabels == expected.testLabels
        platform.main == expected.main

        where:
        config                                                                            | isMain | buildVersion                  | expected
        [labels: null, testEnvironment: null, testLabels: null, dockerArgs: null]         | true   | buildVersion("os", "v")       | new Platform("os_Unity_v", ".", "v", "macos", false, "", [], [], true, false)
        [checkDir: "dir"]                                                                 | false  | buildVersion("os2", "v")      | new Platform("os2_Unity_v", "dir", "v", "macos", false, "", [], [], false, false)
        [checkoutDir: "dir"]                                                              | false  | buildVersion("os", "v")       | new Platform("dir", ".", "v", "macos", false, "", [], [], false, false)
        [checkoutDir: "dir", clearWs: true]                                               | false  | buildVersion("os", "v")       | new Platform("dir", ".", "v", "macos", false, "", [], [], false, true)
        [clearWs: true]                                                                   | false  | buildVersion("os3", "v")      | new Platform("os3_Unity_v", ".", "v", "macos", false, "", [], [], false, true)
        [testEnvironment: ["t=a", "t2=b"]]                                                | false  | buildVersion("os", "v")       | new Platform("os_Unity_v", ".", "v", "macos", false, "", ["t=a", "t2=b"], [], false, false)
        [testEnvironment: ["t=a", "t2=b"], testLabels: ["l", "l2"]]                       | true   | buildVersion("os", "v")       | new Platform("os_Unity_v", ".", "v", "macos", false, "", ["t=a", "t2=b"], ["l", "l2"], true, false)
        [labels: "label", testLabels: ["t", "t2"]]                                        | false  | buildVersion("os4", "v")      | new Platform("os4_Unity_v", ".", "v", "macos", false, "label", [], ["t", "t2"], false, false)
        [labels: "label", testLabels: [v: ["t", "t2"]]]                                   | false  | buildVersion("os", "v")       | new Platform("os_Unity_v", ".", "v", "macos", false, "label", [], ["t", "t2"], false, false)
        [labels: "label", testLabels: [v: "tag"]]                                         | false  | buildVersion("os", "v")       | new Platform("os_Unity_v", ".", "v", "macos", false, "label", [], ["tag"], false, false)
        [labels: "label", testLabels: [notv: ["t", "t2"]]]                                | false  | buildVersion("os5", "v")      | new Platform("os5_Unity_v", ".", "v", "macos", false, "label", [], [], false, false)
        [labels: "label", testEnvironment: [v: ["t=a", "t2=b"]]]                          | false  | buildVersion("os", "v")       | new Platform("os_Unity_v", ".", "v", "macos", false, "label", ["t=a", "t2=b"], [], false, false)
        [labels: "label", testEnvironment: [notv: ["t=a", "t2=b"]]]                       | false  | buildVersion("os", "v")       | new Platform("os_Unity_v", ".", "v", "macos", false, "label", [], [], false, false)
        [labels: "label", testEnvironment: ["t=a", "t2=b"]]                               | false  | buildVersion("os", "v")       | new Platform("os_Unity_v", ".", "v", "macos", false, "label", ["t=a", "t2=b"], [], false, false)
        [labels: "label", testEnvironment: ["t=a", "t2=b"], testLabels: ["l", "l2"]]      | false  | buildVersion("os6", "v")      | new Platform("os6_Unity_v", ".", "v", "macos", false, "label", ["t=a", "t2=b"], ["l", "l2"], false, false)
        [labels: "label", testEnvironment: ["t=a", "t2=b"], testLabels: ["l", "l2"]]      | false  | buildVersion("os", "v", "lv") | new Platform("os_Unity_v_lv", ".", "v", "macos", false, "label", ["t=a", "t2=b"], ["l", "l2"], false, false)
        [labels: null, testEnvironment: null, testLabels: null, dockerArgs: [not: "nul"]] | false  | buildVersion("os", "v")       | new Platform("os_Unity_v", ".", "v", "macos", false, "", [], [], false, false)
        [labels: null, testEnvironment: null, testLabels: null, dockerArgs: [not: "nul"]] | false  | buildVersion("os", "v", "lv") | new Platform("os_Unity_v_lv", ".", "v", "macos", false, "", [], [], false, false)
    }

    @Unroll("creates platform named #platName from js config map #config")
    def "creates platform from js config map"() {
        given: "a configuration map"
        and: "a platform name"

        when: "generating new platform"
        def platform = Platform.forJS(platName, config, isMain)

        then: "generated platform is valid and matches map values"
        platform.checkDirectory == expected.checkDirectory
        platform.name == expected.name
        platform.os == expected.os
        platform.os == platform.name
        platform.runsOnDocker == platform.runsOnDocker
        platform.labels == expected.labels
        platform.testEnvironment == expected.testEnvironment
        platform.testLabels == expected.testLabels

        where:
        config                                                                            | isMain | platName | expected
        [labels: null, testEnvironment: null, testLabels: null, dockerArgs: null]         | true   | "name"   | new Platform(".", ".", "name", "name", false, "", [], [], true, false)
        [checkDir: "dir"]                                                                 | false  | "name"   | new Platform(".", "dir", "name", "name", false, "", [], [], false, false)
        [checkoutDir: "dir"]                                                              | false  | "name"   | new Platform("dir", ".", "name", "name", false, "", [], [], false, false)
        [checkoutDir: "dir", clearWs: true]                                               | false  | "name"   | new Platform("dir", ".", "name", "name", false, "", [], [], false, true)
        [clearWs: true]                                                                   | false  | "name"   | new Platform("dir", ".", "name", "name", false, "", [], [], false, true)
        [testEnvironment: ["t=a", "t2=b"]]                                                | false  | "name"   | new Platform(".", ".", "name", "name", false, "", ["t=a", "t2=b"], [], false, false)
        [testEnvironment: ["t=a", "t2=b"], testLabels: ["l", "l2"]]                       | true   | "name"   | new Platform(".", ".", "name", "name", false, "", ["t=a", "t2=b"], ["l", "l2"], true, false)
        [labels: "label", testLabels: ["t", "t2"]]                                        | false  | "name"   | new Platform(".", ".", "name", "name", false, "label", [], ["t", "t2"], false, false)
        [labels: "label", testLabels: [name: ["t", "t2"]]]                                | false  | "name"   | new Platform(".", ".", "name", "name", false, "label", [], ["t", "t2"], false, false)
        [labels: "label", testLabels: [notname: ["t", "t2"]]]                             | false  | "name"   | new Platform(".", ".", "name", "name", false, "label", [], [], false, false)
        [labels: "label", testEnvironment: [name: ["t=a", "t2=b"]]]                       | false  | "name"   | new Platform(".", ".", "name", "name", false, "label", ["t=a", "t2=b"], [], false, false)
        [labels: "label", testEnvironment: [notname: ["t=a", "t2=b"]]]                    | false  | "name"   | new Platform(".", ".", "name", "name", false, "label", [], [], false, false)
        [labels: "label", testEnvironment: ["t=a", "t2=b"]]                               | false  | "name"   | new Platform(".", ".", "name", "name", false, "label", ["t=a", "t2=b"], [], false, false)
        [labels: "label", testEnvironment: ["t=a", "t2=b"], testLabels: ["l", "l2"]]      | false  | "name"   | new Platform(".", ".", "name", "name", false, "label", ["t=a", "t2=b"], ["l", "l2"], false, false)
        [labels: null, testEnvironment: null, testLabels: null, dockerArgs: [not: "nul"]] | false  | "name"   | new Platform(".", ".", "name", "name", false, "", [], [], false, false)
        [labels: null, testEnvironment: null, testLabels: null, dockerArgs: [not: "nul"]] | false  | "linux"  | new Platform(".", ".", "linux", "linux", true, "", [], [], false, false)
    }

    @Unroll
    def "generates test label string"() {
        given: "a valid platform"
        def platform = new Platform(".", ".", "platname", os, false, labels, [], testLabels, false, false)
        when: "generating test label string"
        def labelsStr = platform.generateTestLabelsString()
        then:
        def expLabels = labelsStr.split("&&").collect { it.trim() }
        expLabels.contains(os)
        testLabels ?
                testLabels.every { testLabel -> expLabels.any { it == testLabel } } :
                expLabels.join(" && ").contains(labels ?: "")

        where:
        testLabels    | labels           | os
        null          | null             | "osname"
        null          | "label && other" | "osname"
        ["testlabel"] | null             | "osname"
        ["testlabel"] | "label"          | "osname"
        ["testlabel"] | "label"          | "osname"
    }

    def "creates platform without version environment if build version is set to be found automatically"() {
        given: "build version with 'project_version' version label"
        def autoBuildVer = buildVersion("macos", "project_version")

        and: "config with other test environments"
        def config = [testEnvironment: ["env=a", "env2=b"]]

        when: "creating new WDK platform"
        def platform = Platform.forWDK(autoBuildVer, config, true)

        then: "created platform doesn't have the UVM_UNITY_VERSION environment"
        platform.testEnvironment.find { it.trim().startsWith("UVM_UNITY_VERSION=") } == null
        platform.testEnvironment == config.testEnvironment + ["UNITY_LOG_CATEGORY=check-project_version"]
    }


    def buildVersion(String label, String version, String apiCompatibilityLevel = null) {
        return new BuildVersion(label, version, false, apiCompatibilityLevel)
    }
}
