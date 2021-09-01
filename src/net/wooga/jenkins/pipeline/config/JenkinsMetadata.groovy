package net.wooga.jenkins.pipeline.config

class JenkinsMetadata {

    final int buildNumber
    final String branchName

    static JenkinsMetadata fromScript(Map bindings) {
        return new JenkinsMetadata(
                bindings["BUILD_NUMBER"] as int,
                bindings["BRANCH_NAME"] as String
        )
    }

    JenkinsMetadata(int buildNumber, String branchName) {
        this.buildNumber = buildNumber
        this.branchName = branchName
    }
}
