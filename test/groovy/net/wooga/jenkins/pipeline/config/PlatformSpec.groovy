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
        def platform = Platform.forJava(platName, config)

        then: "generated platform is valid and matches map values"
        platform.name == expected.name
        platform.os == expected.os
        platform.os == platform.name
        platform.runsOnDocker == platform.runsOnDocker
        platform.labels == expected.labels
        platform.testEnvironment == expected.testEnvironment
        platform.testLabels == expected.testLabels

        where:
        config                                                                            | platName | expected
        [labels: null, testEnvironment: null, testLabels: null, dockerArgs: null]         | "name"   | new Platform("name", "name", false, "", [], [])
        [testEnvironment: ["t=a", "t2=b"]]                                                | "name"   | new Platform("name", "name", false, "", ["t=a", "t2=b"], [])
        [testEnvironment: ["t=a", "t2=b"], testLabels: ["l", "l2"]]                       | "name"   | new Platform("name", "name", false, "", ["t=a", "t2=b"], ["l", "l2"])
        [labels: "label", testLabels: ["t", "t2"]]                                        | "name"   | new Platform("name", "name", false, "label", [], ["t", "t2"])
        [labels: "label", testLabels: [name: ["t", "t2"]]]                                | "name"   | new Platform("name", "name", false, "label", [], ["t", "t2"])
        [labels: "label", testLabels: [notname: ["t", "t2"]]]                             | "name"   | new Platform("name", "name", false, "label", [], [])
        [labels: "label", testEnvironment: [name: ["t=a", "t2=b"]]]                       | "name"   | new Platform("name", "name", false, "label", ["t=a", "t2=b"], [])
        [labels: "label", testEnvironment: [notname: ["t=a", "t2=b"]]]                    | "name"   | new Platform("name", "name", false, "label", [], [])
        [labels: "label", testEnvironment: ["t=a", "t2=b"]]                               | "name"   | new Platform("name", "name", false, "label", ["t=a", "t2=b"], [])
        [labels: "label", testEnvironment: ["t=a", "t2=b"], testLabels: ["l", "l2"]]      | "name"   | new Platform("name", "name", false, "label", ["t=a", "t2=b"], ["l", "l2"])
        [labels: null, testEnvironment: null, testLabels: null, dockerArgs: [not: "nul"]] | "name"   | new Platform("name", "name", false, "", [], [])
        [labels: null, testEnvironment: null, testLabels: null, dockerArgs: [not: "nul"]] | "linux"  | new Platform("linux", "linux", true, "", [], [])
    }


    @Unroll("creates platform from wdk config map #config")
    def "creates platform from wdk config map"() {
        given: "a configuration map for wdk"
        and: "a unity build version"

        when: "generating new platform"
        def buildOS = "macos"
        def platform = Platform.forWDK(buildVersion, buildOS, config)

        then: "generated platform is valid, matches map values and contains unity environment"
        def unityEnv = ["UVM_UNITY_VERSION=${buildVersion.version}", "UNITY_LOG_CATEGORY=check-${buildVersion.version}"].with {
            it.addAll(buildVersion.apiCompatibilityLevel ? ["UNITY_API_COMPATIBILITY_LEVEL=${buildVersion.apiCompatibilityLevel}"] : [])
            return it
        }
        platform.name == expected.name
        platform.os == buildOS
        !platform.runsOnDocker
        platform.labels == expected.labels
        platform.testEnvironment == expected.testEnvironment + unityEnv
        platform.testLabels == expected.testLabels

        where:
        config                                                                            | buildVersion            | expected
        [labels: null, testEnvironment: null, testLabels: null, dockerArgs: null]         | buildVersion("v")       | new Platform("v", "macos", false, "", [], [])
        [testEnvironment: ["t=a", "t2=b"]]                                                | buildVersion("v")       | new Platform("v", "macos", false, "", ["t=a", "t2=b"], [])
        [testEnvironment: ["t=a", "t2=b"], testLabels: ["l", "l2"]]                       | buildVersion("v")       | new Platform("v", "macos", false, "", ["t=a", "t2=b"], ["l", "l2"])
        [labels: "label", testLabels: ["t", "t2"]]                                        | buildVersion("v")       | new Platform("v", "macos", false, "label", [], ["t", "t2"])
        [labels: "label", testLabels: [v: ["t", "t2"]]]                                   | buildVersion("v")       | new Platform("v", "macos", false, "label", [], ["t", "t2"])
        [labels: "label", testLabels: [notv: ["t", "t2"]]]                                | buildVersion("v")       | new Platform("v", "macos", false, "label", [], [])
        [labels: "label", testEnvironment: [v: ["t=a", "t2=b"]]]                          | buildVersion("v")       | new Platform("v", "macos", false, "label", ["t=a", "t2=b"], [])
        [labels: "label", testEnvironment: [notv: ["t=a", "t2=b"]]]                       | buildVersion("v")       | new Platform("v", "macos", false, "label", [], [])
        [labels: "label", testEnvironment: ["t=a", "t2=b"]]                               | buildVersion("v")       | new Platform("v", "macos", false, "label", ["t=a", "t2=b"], [])
        [labels: "label", testEnvironment: ["t=a", "t2=b"], testLabels: ["l", "l2"]]      | buildVersion("v")       | new Platform("v", "macos", false, "label", ["t=a", "t2=b"], ["l", "l2"])
        [labels: "label", testEnvironment: ["t=a", "t2=b"], testLabels: ["l", "l2"]]      | buildVersion("v", "lv") | new Platform("v", "macos", false, "label", ["t=a", "t2=b"], ["l", "l2"])
        [labels: null, testEnvironment: null, testLabels: null, dockerArgs: [not: "nul"]] | buildVersion("v")       | new Platform("v", "macos", false, "", [], [])
        [labels: null, testEnvironment: null, testLabels: null, dockerArgs: [not: "nul"]] | buildVersion("v", "lv") | new Platform("v", "macos", false, "", [], [])
    }

    @Unroll
    def "generates test label string"() {
        given: "a valid platform"
        def platform = new Platform("platname", os, false, labels, [], testLabels)
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
    }


    def buildVersion(String version, String apiCompatibilityLevel = null) {
        return new BuildVersion(version, false, apiCompatibilityLevel)
    }
}
