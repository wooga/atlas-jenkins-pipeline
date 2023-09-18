package net.wooga.jenkins.pipeline.setup

import net.wooga.jenkins.pipeline.config.PipelineConventions
import net.wooga.jenkins.pipeline.model.Gradle

class Setups {

    Object jenkins
    Gradle gradle


    Setups(Object jenkinsScript, Gradle gradle) {
        this.jenkins = jenkinsScript
        this.gradle = gradle
    }

    static Setups create(Object jenkinsScript, Gradle gradle) {
        return new Setups(jenkinsScript, gradle)
    }

    def wdk(String releaseType, String releaseScope, PipelineConventions conventions = PipelineConventions.standard) {
        String setupTask = conventions.setupTask
        String workingDir = conventions.workingDir
        jenkins.dir(workingDir) {
            gradle.wrapper("-Prelease.stage=${releaseType.trim()} " +
                    "-Prelease.scope=${releaseScope.trim()} ${setupTask}")
        }

    }

}
