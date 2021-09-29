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

    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        JenkinsMetadata that = (JenkinsMetadata) o

        if (buildNumber != that.buildNumber) return false
        if (branchName != that.branchName) return false

        return true
    }

    int hashCode() {
        int result
        result = buildNumber
        result = 31 * result + (branchName != null ? branchName.hashCode() : 0)
        return result
    }
}
