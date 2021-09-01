package net.wooga.jenkins.pipeline.step

import net.wooga.jenkins.pipeline.run.StepRunner

trait Step {

    String getGradleArgs() {
        return ""
    }

    void run(StepRunner runner) {
        runner.run(gradleArgs)
    }
}