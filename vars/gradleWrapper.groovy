#!/usr/bin/env groovy

def cleanupCommand(String command) {
   regex = /-P[\w\d.]+=\s/
   command.replaceAll(regex, '')
}

/**
 * execute gradlew or gradlew.bat based on current os
 */
def call(String command, Boolean returnStatus = false, Boolean returnStdout = false) {
    if(isUnix()) {
        return sh(script: "./gradlew ${cleanupCommand(command)}", returnStdout:returnStdout, returnStatus:returnStatus)
    }
    else {
        return bat(script: "gradlew.bat ${cleanupCommand(command)}", returnStdout:returnStdout, returnStatus:returnStatus)
    }
}
