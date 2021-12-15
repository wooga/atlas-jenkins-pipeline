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
        buildVersion.version == expected.version
        buildVersion.optional == expected.optional
        buildVersion.apiCompatibilityLevel == expected.apiCompatibilityLevel

        where:
        obj                                                                 | expected
        new BuildVersion("2021", true, "a")                                 | new BuildVersion("2021", true, "a")
                { -> new BuildVersion("2021", true, "a") } | new BuildVersion("2021", true, "a")
        "2020"                                                              | new BuildVersion("2020", false, null)
                { -> "2020" } | new BuildVersion("2020", false, null)
        [version: "2019"]                                                   | new BuildVersion("2019", false, null)
                { -> [version: "2019"] } | new BuildVersion("2019", false, null)
        [version: "2019", optional: true]                                   | new BuildVersion("2019", true, null)
        [version: "2019", apiCompatibilityLevel: "net_4_6"]                 | new BuildVersion("2019", false, "net_4_6")
        [version: "2019", optional: true, apiCompatibilityLevel: "net_4_6"] | new BuildVersion("2019", true, "net_4_6")
    }

    @Unroll("parses buildVersion #obj with version from ProjectVersion txt file")
    def "parses buildVersion with version from ProjectVersion txt file"() {
        given: "a valid ProjectVersion.txt file"
        def projectVersionFile = new File("test/resources/ProjectVersion.txt")
        projectVersionFile.delete()
        projectVersionFile << """m_EditorVersion: ${projectVersion}
m_EditorVersionWithRevision: ${projectVersion} (ca5b14067cec)
"""
        projectVersionFile.deleteOnExit()
        and: "A unity version file object"
        def unityVersionFile = new UnityVersionFile(projectVersionFile.path, projectVersionFile.text)
        when:
        def buildVersion = BuildVersion.parse(obj, unityVersionFile)
        then:
        buildVersion.version == expected.version
        buildVersion.optional == expected.optional
        buildVersion.apiCompatibilityLevel == expected.apiCompatibilityLevel
        where:
        obj                                                                            | projectVersion | expected
        "project_version"                                                              | "2020.2.1f"    | new BuildVersion("2020.2.1f", false, null)
        new BuildVersion("project_version", true, "a")                                 | "2020.2.1f"    | new BuildVersion("2020.2.1f", true, "a")
        [version: "project_version", optional: true, apiCompatibilityLevel: "net_4_6"] | "2020.2.1f"    | new BuildVersion("2020.2.1f", true, "net_4_6")
        [version: "project_version"]                                                   | "2020.2.1f"    | new BuildVersion("2020.2.1f", false, null)
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
        def buildVersion = new BuildVersion(version, optional, apiCompatibilityLevel)
        when:
        def dirName = buildVersion.toDirectoryName()
        then:
        dirName == expected

        where:
        version | optional | apiCompatibilityLevel | expected
        "2019"  | false    | null                  | "2019"
        "2019"  | true     | null                  | "2019_optional"
        "2019"  | false    | "level"               | "2019_level"
        "2019"  | true     | "level"               | "2019_optional_level"
    }

    @Unroll
    def "generates step label"() {
        given: "valid build version object"
        def buildVersion = new BuildVersion(version, optional, apiCompatibilityLevel)
        when:
        def label = buildVersion.toLabel()
        then:
        label == expected

        where:
        version | optional | apiCompatibilityLevel | expected
        "2019"  | false    | null                  | "2019"
        "2019"  | true     | null                  | "2019 (optional)"
        "2019"  | false    | "level"               | "2019 (level)"
        "2019"  | true     | "level"               | "2019 (optional) (level)"
    }


}
