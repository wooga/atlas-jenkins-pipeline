#!/usr/bin/env groovy

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildWDKJS                                                                                                      //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


def call(Map config = [platforms:['osx'], testEnvironment:[], coverallsToken:null]) {
    def platforms = config.platforms
    def mainPlatform = platforms[0]

    pipeline {
        agent none

        options {
            buildDiscarder(logRotator(artifactNumToKeepStr:'40'))
        }

        parameters {
            choice(choices: ["snapshot", "candidate", "final"], description: 'Choose the distribution type', name: 'RELEASE_TYPE')
            choice(choices: ["", "patch", "minor", "major"], description: 'Choose the change scope', name: 'RELEASE_SCOPE')
            choice(choices: ["info", "quiet", "warn", "debug"], description: 'Choose the log level', name: 'LOG_LEVEL')
            booleanParam( defaultValue: false, description: 'Build & Publish Artifacts', name: 'BUILD_ARTIFACTS')
        }

        stages {
            stage('Preparation') {
                agent any

                steps {
                    sendSlackNotification "STARTED", true
                }
            }

            stage("check") {

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

                when {
                    beforeAgent true
                    expression {
                        return params.RELEASE_TYPE == "snapshot"
                    }
                }

                steps {
                    script {
                        def stepsForParallel = platforms.collectEntries {
                            def environment = []
                            if(config.testEnvironment) {
                                if(config.testEnvironment instanceof List) {
                                    environment = config.testEnvironment
                                }
                                else {
                                    environment = (config.testEnvironment[it]) ?: []
                                }
                            }

                            ["check ${it}" : transformIntoCheckStep(it, environment, config.coverallsToken)]
                        }

                        parallel stepsForParallel
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

                steps {
                    gradleWrapper "${params.RELEASE_TYPE.trim()} -Prelease.stage=${params.RELEASE_TYPE.trim()} -Prelease.scope=${params.RELEASE_SCOPE} -x check"
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

/**
 * Creates a step closure from a unity version string.
 **/
def transformIntoCheckStep(platform, testEnvironment, coverallsToken) {
    return {
        node("${platform} && atlas") {
            try {
                checkout scm
                withEnv(["TRAVIS_JOB_NUMBER=${BUILD_NUMBER}.${platform.toUpperCase()}"]) {
                    withEnv(testEnvironment) {
                        gradleWrapper "check"
                    }
                }
            }
            finally {
                junit allowEmptyResults: true, testResults: 'build/test-results/**/*.xml'
                publishHTML([
                        allowMissing: true,
                        alwaysLinkToLastBuild: true,
                        keepAll: true,
                        reportDir: 'coverage/lcov-report',
                        reportFiles: 'index.html',
                        reportName: 'Coverage',
                        reportTitles: ''
                ])
            }
        }
    }
}
