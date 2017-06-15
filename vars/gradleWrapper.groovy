#!/usr/bin/env groovy

/**
 * execute gradlew or gradlew.bat based on current os
 */
def call(String command, Boolean returnStatus = false, Boolean returnStdout = false) {
    String osName = System.getProperty("os.name").toLowerCase()
    if((osName.contains("windows"))) {
        return bat(script: "gradlew.bat $command", returnStdout:returnStdout, returnStatus:returnStatus)    
    }
    else {
        return sh(script: "gradlew $command", returnStdout:returnStdout, returnStatus:returnStatus)    
    }    
}