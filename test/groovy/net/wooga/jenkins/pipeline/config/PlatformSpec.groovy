package net.wooga.jenkins.pipeline.config

import spock.lang.Specification
import spock.lang.Unroll

class PlatformSpec extends Specification {

    @Unroll
    def "creates platform from config map"() {
        given: "a configuration map"
        and: "a platform name"
        when: "generating new platform"
        def platform = Platform.fromConfigMap(platName, config)
        then: "generated platform is valid and matches map values"
        platform.name == expected.name
        platform.labels == expected.labels
        platform.testEnvironment == expected.testEnvironment
        platform.testLabels == expected.testLabels
        where:
        config                                                                       | platName | expected
        [labels: null, testEnvironment: null, testLabels: null]                      | "name"   | new Platform("name", "", [], [])
        [testEnvironment: ["t=a", "t2=b"]]                                           | "name"   | new Platform("name", "", ["t=a", "t2=b"], [])
        [testEnvironment: ["t=a", "t2=b"], testLabels: ["l", "l2"]]                  | "name"   | new Platform("name", "", ["t=a", "t2=b"], ["l", "l2"])
        [labels: "label", testLabels: ["t", "t2"]]                                   | "name"   | new Platform("name", "label", [], ["t", "t2"])
        [labels: "label", testLabels: [name: ["t", "t2"]]]                           | "name"   | new Platform("name", "label", [], ["t", "t2"])
        [labels: "label", testLabels: [notname: ["t", "t2"]]]                        | "name"   | new Platform("name", "label", [], [])
        [labels: "label", testEnvironment: [name: ["t=a", "t2=b"]]]                  | "name"   | new Platform("name", "label", ["t=a", "t2=b"], [])
        [labels: "label", testEnvironment: [notname: ["t=a", "t2=b"]]]               | "name"   | new Platform("name", "label", [], [])
        [labels: "label", testEnvironment: ["t=a", "t2=b"]]                          | "name"   | new Platform("name", "label", ["t=a", "t2=b"], [])
        [labels: "label", testEnvironment: ["t=a", "t2=b"], testLabels: ["l", "l2"]] | "name"   | new Platform("name", "label", ["t=a", "t2=b"], ["l", "l2"])
    }

    @Unroll
    def "generates test label string"() {
        given: "a valid platform"
        def platform = new Platform(platName, labels, [], testLabels)
        when: "generating test label string"
        def labelsStr = platform.generateTestLabelsString()
        then:
        def expLabels = labelsStr.split("&&").collect { it.trim() }
        expLabels.contains(platName)
        onDocker ? expLabels.contains("docker") : true
        testLabels ?
                testLabels.every { testLabel -> expLabels.any { it == testLabel } } :
                expLabels.join(" && ").contains(labels?:"")

        where:
        testLabels    | labels           | platName   | onDocker
        null          | null             | "platname" | false
        null          | "label && other" | "platname" | false
        ["testlabel"] | null             | "platname" | false
        ["testlabel"] | "label"          | "platname" | false
        ["testlabel"] | "label"          | "linux"    | true
    }
}
