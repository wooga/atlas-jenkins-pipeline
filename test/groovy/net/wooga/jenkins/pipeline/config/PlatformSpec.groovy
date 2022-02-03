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
        platform.directory == expected.directory
        platform.name == expected.name
        platform.os == expected.os
        platform.os == platform.name
        platform.runsOnDocker == platform.runsOnDocker
        platform.labels == expected.labels
        platform.testEnvironment == expected.testEnvironment
        platform.testLabels == expected.testLabels

        where:
        config                                                                            | isMain | platName | expected
        [labels: null, testEnvironment: null, testLabels: null, dockerArgs: null]         | true   | "name"   | new Platform(".", "name", "name", false, "", [], [], true)
        [checkDir: "dir"]                                                                 | false  | "name"   | new Platform("dir", "name", "name", false, "", [], [], false)
        [testEnvironment: ["t=a", "t2=b"]]                                                | false  | "name"   | new Platform(".", "name", "name", false, "", ["t=a", "t2=b"], [], false)
        [testEnvironment: ["t=a", "t2=b"], testLabels: ["l", "l2"]]                       | true   | "name"   | new Platform(".", "name", "name", false, "", ["t=a", "t2=b"], ["l", "l2"], true)
        [labels: "label", testLabels: ["t", "t2"]]                                        | false  | "name"   | new Platform(".", "name", "name", false, "label", [], ["t", "t2"], false)
        [labels: "label", testLabels: [name: ["t", "t2"]]]                                | false  | "name"   | new Platform(".", "name", "name", false, "label", [], ["t", "t2"], false)
        [labels: "label", testLabels: [notname: ["t", "t2"]]]                             | false  | "name"   | new Platform(".", "name", "name", false, "label", [], [], false)
        [labels: "label", testEnvironment: [name: ["t=a", "t2=b"]]]                       | false  | "name"   | new Platform(".", "name", "name", false, "label", ["t=a", "t2=b"], [], false)
        [labels: "label", testEnvironment: [notname: ["t=a", "t2=b"]]]                    | false  | "name"   | new Platform(".", "name", "name", false, "label", [], [], false)
        [labels: "label", testEnvironment: ["t=a", "t2=b"]]                               | false  | "name"   | new Platform(".", "name", "name", false, "label", ["t=a", "t2=b"], [], false)
        [labels: "label", testEnvironment: ["t=a", "t2=b"], testLabels: ["l", "l2"]]      | false  | "name"   | new Platform(".", "name", "name", false, "label", ["t=a", "t2=b"], ["l", "l2"], false)
        [labels: null, testEnvironment: null, testLabels: null, dockerArgs: [not: "nul"]] | false  | "name"   | new Platform(".", "name", "name", false, "", [], [], false)
        [labels: null, testEnvironment: null, testLabels: null, dockerArgs: [not: "nul"]] | false  | "linux"  | new Platform(".", "linux", "linux", true, "", [], [], false)
    }


    @Unroll("creates platform from wdk config map #config")
    def "creates platform from wdk config map"() {
        given: "a configuration map for wdk"
        and: "a unity build version"

        when: "generating new platform"
        def buildOS = "macos"
        def platform = Platform.forWDK(buildVersion, buildOS, config, isMain)

        then: "generated platform is valid, matches map values and contains unity environment"
        def unityEnv = ["UVM_UNITY_VERSION=${buildVersion.version}", "UNITY_LOG_CATEGORY=check-${buildVersion.version}"].with {
            it.addAll(buildVersion.apiCompatibilityLevel ? ["UNITY_API_COMPATIBILITY_LEVEL=${buildVersion.apiCompatibilityLevel}"] : [])
            return it
        }
        platform.directory == expected.directory
        platform.name == expected.name
        platform.os == buildOS
        !platform.runsOnDocker
        platform.labels == expected.labels
        platform.testEnvironment == expected.testEnvironment + unityEnv
        platform.testLabels == expected.testLabels
        platform.main == expected.main

        where:
        config                                                                            | isMain | buildVersion            | expected
        [labels: null, testEnvironment: null, testLabels: null, dockerArgs: null]         | true   | buildVersion("v")       | new Platform("v", "v", "macos", false, "", [], [], true)
        [testEnvironment: ["t=a", "t2=b"]]                                                | false  | buildVersion("v")       | new Platform("v", "v", "macos", false, "", ["t=a", "t2=b"], [], false)
        [testEnvironment: ["t=a", "t2=b"], testLabels: ["l", "l2"]]                       | true   | buildVersion("v")       | new Platform("v", "v", "macos", false, "", ["t=a", "t2=b"], ["l", "l2"], true)
        [labels: "label", testLabels: ["t", "t2"]]                                        | false  | buildVersion("v")       | new Platform("v", "v", "macos", false, "label", [], ["t", "t2"], false)
        [labels: "label", testLabels: [v: ["t", "t2"]]]                                   | false  | buildVersion("v")       | new Platform("v", "v", "macos", false, "label", [], ["t", "t2"], false)
        [labels: "label", testLabels: [v: "tag"]]                                         | false  | buildVersion("v")       | new Platform("v", "v", "macos", false, "label", [], ["tag"], false)
        [labels: "label", testLabels: [notv: ["t", "t2"]]]                                | false  | buildVersion("v")       | new Platform("v", "v", "macos", false, "label", [], [], false)
        [labels: "label", testEnvironment: [v: ["t=a", "t2=b"]]]                          | false  | buildVersion("v")       | new Platform("v", "v", "macos", false, "label", ["t=a", "t2=b"], [], false)
        [labels: "label", testEnvironment: [notv: ["t=a", "t2=b"]]]                       | false  | buildVersion("v")       | new Platform("v", "v", "macos", false, "label", [], [], false)
        [labels: "label", testEnvironment: ["t=a", "t2=b"]]                               | false  | buildVersion("v")       | new Platform("v", "v", "macos", false, "label", ["t=a", "t2=b"], [], false)
        [labels: "label", testEnvironment: ["t=a", "t2=b"], testLabels: ["l", "l2"]]      | false  | buildVersion("v")       | new Platform("v", "v", "macos", false, "label", ["t=a", "t2=b"], ["l", "l2"], false)
        [labels: "label", testEnvironment: ["t=a", "t2=b"], testLabels: ["l", "l2"]]      | false  | buildVersion("v", "lv") | new Platform("v_lv", "v", "macos", false, "label", ["t=a", "t2=b"], ["l", "l2"], false)
        [labels: null, testEnvironment: null, testLabels: null, dockerArgs: [not: "nul"]] | false  | buildVersion("v")       | new Platform("v", "v", "macos", false, "", [], [], false)
        [labels: null, testEnvironment: null, testLabels: null, dockerArgs: [not: "nul"]] | false  | buildVersion("v", "lv") | new Platform("v_lv", "v", "macos", false, "", [], [], false)
    }

    @Unroll
    def "generates test label string"() {
        given: "a valid platform"
        def platform = new Platform(".", "platname", os, false, labels, [], testLabels, false)
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


    def buildVersion(String version, String apiCompatibilityLevel = null) {
        return new BuildVersion(version, false, apiCompatibilityLevel)
    }
}
