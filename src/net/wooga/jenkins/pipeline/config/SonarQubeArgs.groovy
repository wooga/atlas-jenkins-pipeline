package net.wooga.jenkins.pipeline.config

class SonarQubeArgs {

    static SonarQubeArgs fromConfigMap(Map config) {
        return new SonarQubeArgs(config.sonarToken as String)
    }

    final String token

    SonarQubeArgs(String token) {
        this.token = token
    }

    boolean shouldRunSonarQube() {
        return (token != null)
    }

    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        SonarQubeArgs that = (SonarQubeArgs) o

        if (token != that.token) return false

        return true
    }

    int hashCode() {
        return (token != null ? token.hashCode() : 0)
    }
}
