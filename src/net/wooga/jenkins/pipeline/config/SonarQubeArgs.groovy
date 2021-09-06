package net.wooga.jenkins.pipeline.config

class SonarQubeArgs {

    static SonarQubeArgs fromConfigMap(Map config) {
        String branchPattern = config.sonarQubeBranchPattern ?: "^(master|main)\$"
        return new SonarQubeArgs(config.sonarToken as String, branchPattern)
    }

    final String token
    final String branchPattern;

    SonarQubeArgs(String token, String branchPattern) {
        this.token = token
        this.branchPattern = branchPattern
    }

    boolean shouldRunSonarQube(String branchName) {
        return (token != null && branchName =~ branchPattern)
    }

    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        SonarQubeArgs that = (SonarQubeArgs) o

        if (branchPattern != that.branchPattern) return false
        if (token != that.token) return false

        return true
    }

    int hashCode() {
        int result
        result = (token != null ? token.hashCode() : 0)
        result = 31 * result + (branchPattern != null ? branchPattern.hashCode() : 0)
        return result
    }
}
