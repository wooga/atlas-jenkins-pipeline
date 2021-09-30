package net.wooga.jenkins.pipeline.config

class JenkinsMetadata {

    final int buildNumber
    final String branchName
    final String prChangeId

    static JenkinsMetadata fromScript(Object jenkinsScript) {
        if(jenkinsScript.BUILD_NUMBER == null) {
            throw new IllegalStateException("Jenkins script object must have a BUILD_NUMBER property")
        }
        return new JenkinsMetadata(
                jenkinsScript.BUILD_NUMBER as int,
                jenkinsScript.BRANCH_NAME as String,
                jenkinsScript.env?.getAt("CHANGE_ID") as String //nullable
        )
    }

    JenkinsMetadata(int buildNumber, String branchName, String prChangeId) {
        this.prChangeId = prChangeId
        this.buildNumber = buildNumber
        this.branchName = branchName
    }

    boolean isPR() {
        return prChangeId != null
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
