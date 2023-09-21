#!/usr/bin/env groovy
import net.wooga.jenkins.pipeline.model.EnvVars
import net.wooga.jenkins.pipeline.model.Gradle

/**
 * execute gradlew or gradlew.bat based on current os
 */
def call(String command, Boolean returnStatus = false, Boolean returnStdout = false, Map<String, ?> environment=[:]) {
    def gradle = Gradle.fromJenkins(this,
                new EnvVars(environment),
                params.LOG_LEVEL?: env.LOG_LEVEL as String,
                params.STACK_TRACE? params.STACK_TRACE as Boolean : false,
                params.REFRESH_DEPENDENCIES? params.REFRESH_DEPENDENCIES as Boolean : false)
    gradle.wrapper(env.UMASK as String, command, returnStatus, returnStdout)
}

def call(Map args) {
    call(args.command?.toString(),
        (args.returnStatus?: false) as Boolean,
        (args.returnStdout?: false) as Boolean,
        (args.environment?: [:]) as Map<String, ?>
    )
}


