package net.wooga.jenkins.pipeline.check

class Sonarqube {

    final String token

    Sonarqube(String token) {
        this.token = token
    }

    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        Sonarqube sonarqube = (Sonarqube) o

        if (token != sonarqube.token) return false

        return true
    }

    int hashCode() {
        return (token != null ? token.hashCode() : 0)
    }
}
