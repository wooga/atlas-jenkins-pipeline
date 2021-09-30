package net.wooga.jenkins.pipeline.config

import spock.lang.Specification

class JenkinsMetadataSpec extends Specification {

    def "creates jenkins metadata object from object with jenkins script properties"() {
        given: "a object with jenkins script properties"
        def propsObj = [BUILD_NUMBER: buildNumber, BRANCH_NAME: branchName, env:[CHANGE_ID: prChangeId]]

        when:
        def metadata = JenkinsMetadata.fromScript(propsObj)

        then:
        metadata.buildNumber == buildNumber
        metadata.branchName == branchName
        metadata.prChangeId == prChangeId
        metadata.isPR() == (prChangeId != null)

        where:
        buildNumber | branchName | prChangeId
        1           | "branch"   | "123"
        1           | "branch"   | null
    }

    def "fails to jenkins metadata object if buildNumber does not exists"() {
        given: "a object with jenkins script properties without BUILD_NUMBER"
        def propsObj = [BRANCH_NAME: "branch", env:[CHANGE_ID: "change"]]

        when:
        JenkinsMetadata.fromScript(propsObj)

        then:
        def e = thrown(IllegalStateException)
        e.message == "Jenkins script object must have a BUILD_NUMBER property"
    }

}
