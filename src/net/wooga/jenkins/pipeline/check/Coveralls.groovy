package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.model.Gradle

class Coveralls {

    final Object jenkins
    final String task

    Coveralls(Object jenkins, String task) {
        this.jenkins = jenkins
        this.task = task
    }

    void runGradle(Gradle gradle, String token) {
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
}
