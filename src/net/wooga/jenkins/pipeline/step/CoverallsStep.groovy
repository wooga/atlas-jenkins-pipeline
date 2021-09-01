package net.wooga.jenkins.pipeline.step

import net.wooga.jenkins.pipeline.run.RunTooling
import net.wooga.jenkins.pipeline.run.StepRunner
import net.wooga.jenkins.pipeline.config.Platform

class CoverallsStep implements Step {

    private Platform platform
    private String token


    CoverallsStep(Platform platform, String token) {
        this.platform = platform
        this.token = token
    }

    @Override
    String getGradleArgs() {
        return "coveralls"
    }

    @Override
    void run(StepRunner runner) {
        runner.env(["COVERALLS_REPO_TOKEN": token, "COVERALLS_PARALLEL": true])
        runner.gradleRun(getGradleArgs()) { RunTooling it ->
            it.publishHTML([
                allowMissing: true,
                alwaysLinkToLastBuild: true,
                keepAll: true,
                reportDir: 'build/reports/jacoco/test/html',
                reportFiles: 'index.html',
                reportName: "Coverage ${platform.name}",
                reportTitles: ''
            ])
        }
    }
}
