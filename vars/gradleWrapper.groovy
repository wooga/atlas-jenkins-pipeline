#!/usr/bin/env groovy
import net.wooga.jenkins.pipeline.model.Gradle

/**
 * execute gradlew or gradlew.bat based on current os
 */
def call(String command, Boolean returnStatus = false, Boolean returnStdout = false) {
    def gradle = Gradle.fromJenkins(this, params.LOG_LEVEL?: env.LOG_LEVEL as String, params.STACK_TRACE as boolean)
    gradle.wrapper(command, returnStatus, returnStdout)
}
