package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.model.Gradle

class Coveralls {

    final Object jenkins
    final String token

    Coveralls(Object jenkins, String token) {
        this.jenkins = jenkins
        this.token = token
    }

    void maybeRun(Gradle gradle, String task) {
        if (token) {
            jenkins.withEnv(["COVERALLS_REPO_TOKEN=${token}"]) {
                gradle.wrapper(task)
                jenkins.publishHTML([
                        allowMissing: true,
                        alwaysLinkToLastBuild: true,
                        keepAll: true,
                        reportDir: 'build/reports/jacoco/test/html',
                        reportFiles: 'index.html',
                        reportName: "Coverage ${it}",
                        reportTitles: ''
                ])
            }
        }
    }

    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        Coveralls coveralls = (Coveralls) o

        if (jenkins != coveralls.jenkins) return false
        if (token != coveralls.token) return false

        return true
    }

    int hashCode() {
        int result
        result = (jenkins != null ? jenkins.hashCode() : 0)
        result = 31 * result + (token != null ? token.hashCode() : 0)
        return result
    }
}
