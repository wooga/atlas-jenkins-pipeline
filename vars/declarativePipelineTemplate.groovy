#!/usr/bin/env groovy
import net.wooga.jenkins.pipeline.config.PipelineConfig

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildGradlePlugin                                                                                             //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(PipelineConfig config, List<Closure> stageFactoriesList) {
    pipeline {
        agent none

        options {
            buildDiscarder(logRotator(artifactNumToKeepStr: '40'))
        }

        parameters {
            choice(choices: ["snapshot", "rc", "final"], description: 'Choose the distribution type', name: 'RELEASE_TYPE')
            choice(choices: ["", "patch", "minor", "major"], description: 'Choose the change scope', name: 'RELEASE_SCOPE')
            choice(choices: ["", "quiet", "info", "warn", "debug"], description: 'Choose the log level', name: 'LOG_LEVEL')
            booleanParam(defaultValue: false, description: 'Whether to log truncated stacktraces', name: 'STACK_TRACE')
            booleanParam(defaultValue: false, description: 'Whether to refresh dependencies', name: 'REFRESH_DEPENDENCIES')
            booleanParam(defaultValue: false, description: 'Whether to clear workspaces after build', name: 'CLEAR_WS')
        }

        stages {
            stage('Preparation') {
                agent any
                steps {
                    sendSlackNotification "STARTED", true
                    script {
                        for(Closure stageFactory: stageFactoriesList) {
                            def stage = stageFactory() as Closure
                            stage.call()
                        }
                    }
                }
                post {
                    cleanup {
                        script {
                            if (config.mainPlatform.clearWs) {
                                cleanWs()
                            }
                        }
                    }
                }
            }
        }

        post {
            always {
                sendSlackNotification currentBuild.result, true
            }
        }
    }
}
