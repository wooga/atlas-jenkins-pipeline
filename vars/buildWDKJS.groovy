#!/usr/bin/env groovy
import net.wooga.jenkins.pipeline.check.steps.Step

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildWDKJS                                                                                                      //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


def call(Map configMap = [:]) {
    configMap.logLevel = configMap.get("logLevel", params.LOG_LEVEL?: env.LOG_LEVEL as String)
    configMap.showStackTrace = configMap.get("showStackTrace", params.STACK_TRACE as Boolean)
    configMap.refreshDependencies = configMap.get("refreshDependencies", params.REFRESH_DEPENDENCIES as Boolean)
    configMap.clearWs = configMap.get("clearWs", params.CLEAR_WS as boolean)
    def config  = configs().jsWDK(configMap, this)
    def platforms = config.platforms
    def mainPlatform = platforms[0]

    pipeline {
        agent none

        options {
            buildDiscarder(logRotator(artifactNumToKeepStr:'40'))
        }

        parameters {
            choice(choices: ["snapshot", "rc", "final"], description: 'Choose the distribution type', name: 'RELEASE_TYPE')
            choice(choices: ["", "patch", "minor", "major"], description: 'Choose the change scope', name: 'RELEASE_SCOPE')
            choice(choices: ["", "quiet", "info", "warn", "debug"], description: 'Choose the log level', name: 'LOG_LEVEL')
            booleanParam(defaultValue: false, description: 'Whether to log truncated stacktraces', name: 'STACK_TRACE')
            booleanParam(defaultValue: false, description: 'Whether to refresh dependencies', name: 'REFRESH_DEPENDENCIES')
            booleanParam(defaultValue: false, description: 'Force Build & Publish Artifacts', name: 'BUILD_ARTIFACTS')
            booleanParam(defaultValue: false, description: 'Whether to clear workspaces after build', name: 'CLEAR_WS')
        }

        environment {
            GRGIT                 = credentials('github_access')
            GRGIT_USER            = "${GRGIT_USR}"
            GRGIT_PASS            = "${GRGIT_PSW}"
            GITHUB_LOGIN          = "${GRGIT_USR}"
            GITHUB_PASSWORD       = "${GRGIT_PSW}"
            NODE_RELASE_NPM       = credentials('atlas_npm_credentials')
            NODE_RELEASE_NPM_USER = "${NODE_RELASE_NPM_USR}"
            NODE_RELEASE_NPM_PASS = "${NODE_RELASE_NPM_PSW}"
            NODE_RELEASE_NPM_AUTH_URL = "${NODE_RELEASE_NPM_AUTH_URL}"
        }

        stages {
            stage('Preparation') {
                agent any
                steps {
                    sendSlackNotification "STARTED", true
                }
            }

            stage("check") {
                when {
                    beforeAgent true
                    expression {
                        return params.RELEASE_TYPE == "snapshot"
                    }
                }

                steps {
                    script {
                        Step checkTemplate = jsCheckTemplate(config.pipelineTools.checks, config.checkArgs, config.conventions)
                        def checksForParallel = parallelize(checkTemplate, config.platforms, config.conventions.javaParallelPrefix)
                        parallel checksForParallel
                    }
                }
                post {
                    success {
                        script {
                            if(config.checkArgs.coveralls.token) {
                                httpRequest httpMode: 'POST', ignoreSslErrors: true, validResponseCodes: '100:599', url: "https://coveralls.io/webhook?repo_token=${config.checkArgs.coveralls.token}"
                            }
                        }
                    }
                }
            }

            stage('publish') {
                when {
                    beforeAgent true
                    expression {
                        return params.RELEASE_TYPE != "snapshot" || params.BUILD_ARTIFACTS
                    }
                }

                agent {
                    label "$mainPlatform && atlas"
                }

                steps {
                    script {
                        def publisher = config.pipelineTools.createPublishers(params.RELEASE_TYPE, params.RELEASE_SCOPE)
                        publisher.npm('atlas_npm_credentials')
                    }
                }
                post {
                    always {
                        archiveArtifacts artifacts: '**/build/logs/*.log', allowEmptyArchive: true
                        archiveArtifacts artifacts: '*.tgz', allowEmptyArchive: true
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
