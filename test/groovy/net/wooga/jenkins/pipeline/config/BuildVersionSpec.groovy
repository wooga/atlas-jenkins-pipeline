package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.BuildVersion
import spock.lang.Specification
import spock.lang.Unroll

class BuildVersionSpec extends Specification {


    @Unroll("parses #obj into BuildVersion")
    def "parses build version object from map, self, string or closure"() {
        given: "a parseable object"
        when:
        def buildVersion = BuildVersion.parse(obj)
        then:
        buildVersion.label == expected.label
        buildVersion.version == expected.version
        buildVersion.optional == expected.optional
        buildVersion.apiCompatibilityLevel == expected.apiCompatibilityLevel

        where:
        obj                                                                 | expected
        new BuildVersion("2021", true, "a")                                 | new BuildVersion("macos", "2021", true, "a")
        new BuildVersion("label", "2021", true, "a")                        | new BuildVersion("label", "2021", true, "a")
                { ->
                    new BuildVersion("2021", true, "a")
                }                                                          | new BuildVersion("macos", "2021", true, "a")
        "2020"                                                              | new BuildVersion("macos", "2020", false, null)
                { ->
                    "2020"
                }                                                                                         | new BuildVersion("macos", "2020", false, null)
        [version: "2019"]                                                   | new BuildVersion("macos", "2019", false, null)
                { ->
                    [version: "2019"]
                }                                                                              | new BuildVersion("macos", "2019", false, null)
        [label: "label", version: "2019", optional: true]                   | new BuildVersion("label", "2019", true, null)
        [version: "2019", optional: true]                                   | new BuildVersion("macos", "2019", true, null)
        [version: "2019", apiCompatibilityLevel: "net_4_6"]                 | new BuildVersion("macos", "2019", false, "net_4_6")
        [version: "2019", optional: true, apiCompatibilityLevel: "net_4_6"] | new BuildVersion("macos", "2019", true, "net_4_6")
    }

    @Unroll("fails to parse #obj into buildVersion if no version source is provided")
    def "fails to parse buildVersion if no version source is provided"() {
        given: "a non-parseable object"
        when:
        BuildVersion.parse(obj)
        then:
        def e = thrown(IllegalArgumentException)
        e.message == message
        where:
        obj              | message
        [optional: true] | "Entry ${obj} does not contain version"
        null             | "Entry cannot be null"
    }

    @Unroll
    def "generates directory name"() {
        given: "valid build version object"
        def buildVersion = new BuildVersion(label, version, optional, apiCompatibilityLevel)
        when:
        def dirName = buildVersion.toDirectoryName()
        then:
        dirName == expected

        where:
        label     | version | optional | apiCompatibilityLevel | expected
        "macos"   | "2019"  | false    | null                  | "macos_Unity_2019"
        "linux"   | "2020"  | true     | null                  | "linux_Unity_2020_optional"
        "windows" | "2021"  | false    | "level"               | "windows_Unity_2021_level"
        "other"   | "2015"  | true     | "level"               | "other_Unity_2015_optional_level"
    }

    @Unroll
    def "generates step label"() {
        given: "valid build version object"
        def buildVersion = new BuildVersion(label, version, optional, apiCompatibilityLevel)
        when:
        def description = buildVersion.toDescription()
        then:
        description == expected

        where:
        label     | version | optional | apiCompatibilityLevel | expected
        "windows" | "2019"  | false    | null                  | "windows Unity-2019"
        "macos"   | "2019"  | true     | null                  | "macos Unity-2019 (optional)"
        "linux"   | "2019"  | false    | "level"               | "linux Unity-2019 (level)"
        "label"   | "2019"  | true     | "level"               | "label Unity-2019 (optional) (level)"
    }


}
