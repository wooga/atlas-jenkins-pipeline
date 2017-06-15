#!/usr/bin/env groovy

/**
 * execute gradlew or gradlew.bat based on current os
 */
def call(String command, Boolean returnStatus = false, Boolean returnStdout = false) {
    String osName = System.getProperty("os.name").toLowerCase()

    def gradleWrapper = "gradlew"
    def shellStep = sh 

    if((osName.contains("windows"))) {
        gradleWrapper = "gradlew.bat"
        shellStep = bat
    }
    
    shellStep(script: "$gradleWrapper $command" returnStdout:returnStdout, returnStatus:returnStatus)
}