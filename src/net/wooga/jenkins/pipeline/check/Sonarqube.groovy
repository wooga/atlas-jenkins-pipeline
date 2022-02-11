package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.model.Gradle

class Sonarqube {

    final String token

    Sonarqube(String token) {
        this.token = token
    }

    void maybeRun(Gradle gradle, String task, String branchName="") {
        if(token != null) {
            branchName = branchName == null? "" : branchName
            gradle.wrapper(task + " -Dsonar.login=${token}" +
                                     " -Pgithub.branch.name=${branchName.trim()}" as String)
        }
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
