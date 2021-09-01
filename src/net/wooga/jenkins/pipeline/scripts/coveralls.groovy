package net.wooga.jenkins.pipeline.scripts

def call(String token) {
    if (token) {
        withEnv(["COVERALLS_REPO_TOKEN=${token}"]) {
            gradleWrapper "coveralls"
            publishHTML([
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

