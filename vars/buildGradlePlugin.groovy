#!/usr/bin/env groovy

import net.wooga.jenkins.pipeline.TestHelper

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildGradlePlugin                                                                                             //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map config = [:]) {
  //set config defaults
  config.platforms = config.platforms ?: config.plaforms ?: ['macos','windows']
  config.testEnvironment = config.testEnvironment ?: []
  config.testLabels = config.testLabels ?: []
  config.labels = config.labels ?: ''
  config.sonarQubeBranchPattern = config.sonarQubeBranchPattern ?: "^(master|main)\$"
  config.dockerArgs = config.dockerArgs ?: [:]
  config.dockerArgs.dockerFileName = config.dockerArgs.dockerFileName ?: "Dockerfile"
  config.dockerArgs.dockerFileDirectory = config.dockerArgs.dockerFileDirectory ?: "."
  config.dockerArgs.dockerBuildArgs = config.dockerArgs.dockerBuildArgs ?: []
  config.dockerArgs.dockerArgs = config.dockerArgs.dockerArgs ?: []

  def platforms = config.platforms
  def mainPlatform = platforms[0]
  def helper = new TestHelper()

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
      booleanParam(defaultValue: false, description: 'Whether to force sonarqube execution', name: 'RUN_SONAR')
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
            def stepsForParallel = platforms.collectEntries { platform ->
              def environment = []
              def labels = config.labels

              if(config.testEnvironment) {
                if(config.testEnvironment instanceof List) {
                  environment = config.testEnvironment
                }
                else {
                  environment = (config.testEnvironment[platform]) ?: []
                }
              }

              environment << "COVERALLS_PARALLEL=true"

              if(config.testLabels) { //talk w/ manne about this
                if(config.testLabels instanceof List) {
                  labels = config.testLabels
                }
                else {
                  labels = (config.testLabels[platform]) ?: config.labels
                }
              }

              def testConfig = config.clone()
              testConfig.labels = labels

              def checkStep = {
                gradleWrapper "check"
              }
              def finalizeStep = {
                if(!currentBuild.result) {
                  def tasks  = "jacocoTestReport"
                  if (config.coverallsToken) {
                    tasks += " coveralls"
                  }
                  if(config.sonarToken && (BRANCH_NAME =~ config.sonarQubeBranchPattern || params.RUN_SONAR)) {
                    tasks += " sonarqube -Dsonar.login=${config.sonarToken}"
                  }
                  withEnv(["COVERALLS_REPO_TOKEN=${config.coverallsToken}"]) {
                    gradleWrapper tasks
                    publishHTML([
                                allowMissing: true,
                                alwaysLinkToLastBuild: true,
                                keepAll: true,
                                reportDir: 'build/reports/jacoco/test/html',
                                reportFiles: 'index.html',
                                reportName: "Coverage ${it}",
                                reportTitles: ''
                                ])
                  }
                }
                junit allowEmptyResults: true, testResults: "**/build/test-results/**/*.xml"
                cleanWs()
              }

              ["check ${platform}" : helper.transformIntoCheckStep(platform, environment, config.coverallsToken, testConfig, checkStep, finalizeStep)]
            }

            parallel stepsForParallel
          }
        }
        post {
          success {
            script {
              if(config.coverallsToken) {
                httpRequest httpMode: 'POST', ignoreSslErrors: true, url: "https://coveralls.io/webhook?repo_token=${config.coverallsToken}"
              }
            }
          }
       }
     }


      stage('publish') {
        when {
          beforeAgent true
          expression {
            return params.RELEASE_TYPE != "snapshot"
          }
        }

        agent {
          label "$mainPlatform && atlas"
        }

        environment {
          GRADLE_PUBLISH_KEY    = credentials('gradle.publish.key')
          GRADLE_PUBLISH_SECRET = credentials('gradle.publish.secret')
          GRGIT                 = credentials('github_up')

          GRGIT_USER            = "${GRGIT_USR}"
          GRGIT_PASS            = "${GRGIT_PSW}"
          GITHUB_LOGIN          = "${GRGIT_USR}"
          GITHUB_PASSWORD       = "${GRGIT_PSW}"
        }

        steps {
          gradleWrapper "${params.RELEASE_TYPE.trim()} -Pgradle.publish.key=${GRADLE_PUBLISH_KEY} -Pgradle.publish.secret=${GRADLE_PUBLISH_SECRET} -Prelease.stage=${params.RELEASE_TYPE.trim()} -Prelease.scope=${params.RELEASE_SCOPE} -x check"
        }

        post {
          cleanup {
            cleanWs()
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
