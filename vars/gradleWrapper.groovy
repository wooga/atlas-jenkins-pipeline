#!/usr/bin/env groovy
import net.wooga.jenkins.pipeline.model.Gradle

/**
 * execute gradlew or gradlew.bat based on current os
 */
def call(String command, Boolean returnStatus = false, Boolean returnStdout = false) {
    Gradle gradle = Gradle.fromJenkins(this)
    gradle.wrapper(command, returnStatus, returnStdout)
}
