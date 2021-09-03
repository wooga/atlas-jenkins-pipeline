package net.wooga.jenkins.pipeline.config

class JenkinsMetadata {

    final int buildNumber
    final String branchName

    static JenkinsMetadata fromScript(Object jenkinsScript) {
        return new JenkinsMetadata(
                jenkinsScript.BUILD_NUMBER as int,
                jenkinsScript.BRANCH_NAME as String
        )
    }

    JenkinsMetadata(int buildNumber, String branchName) {
        this.buildNumber = buildNumber
        this.branchName = branchName
    }
}
