package net.wooga.jenkins.pipeline.check

class Coveralls {

    Object jenkins

    Coveralls(Object jenkins) {
        this.jenkins = jenkins
    }

    void runGradle(Gradle gradle, String token) {
        if (token) {
            jenkins.withEnv(["COVERALLS_REPO_TOKEN=${token}"]) {
                gradle.wrapper("coveralls")
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
