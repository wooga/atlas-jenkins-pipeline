#!/usr/bin/env groovy

import net.wooga.jenkins.pipeline.TestHelper

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildJavaLibrary                                                                                              //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map config = [:]) {
  //set config defaults
  config.platforms = config.plaforms ?: ['osx','windows']
  config.platforms = config.platforms ?: ['osx','windows']
  config.testEnvironment = config.testEnvironment ?: []
  config.testLabels = config.testLabels ?: []
  config.labels = config.labels ?: ''
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
      choice(choices: "snapshot\nrc\nfinal", description: 'Choose the distribution type', name: 'RELEASE_TYPE')
       choice(choices: "\npatch\nminor\nmajor", description: 'Choose the change scope', name: 'RELEASE_SCOPE')
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
            def stepsForParallel = platforms.collectEntries {
              def environment = []
              def labels = config.labels

              if(config.testEnvironment) {
                if(config.testEnvironment instanceof List) {
                  environment = config.testEnvironment
                }
                else {
                  environment = (config.testEnvironment[it]) ?: []
                }
              }

              environment << "COVERALLS_PARALLEL=true"

              if(config.testLabels) {
                if(config.testLabels instanceof List) {
                  labels = config.testLabels
                }
                else {
                  labels = (config.testLabels[it]) ?: config.labels
                }
              }

              def testConfig = config.clone()
              testConfig.labels = labels

              def checkStep = { gradleWrapper "check" }
              def finalizeStep = {
                if(!currentBuild.result) {
                  def command = (config.coverallsToken) ? "jacocoTestReport coveralls" : "jacocoTestReport"
                  withEnv(["COVERALLS_REPO_TOKEN=${config.coverallsToken}"]) {
                    gradleWrapper command
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
                junit allowEmptyResults: true, testResults: '**/build/test-results/**/*.xml'
                cleanWs()
              }

              ["check ${it}" : helper.transformIntoCheckStep(it, environment, config.coverallsToken, testConfig, checkStep, finalizeStep)]
            }

            parallel stepsForParallel
          }
        }

        post {
          success {
            httpRequest httpMode: 'POST', ignoreSslErrors: true, url: 'https://coveralls.io/webhook?repo_token=${config.coverallsToken}'
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
          BINTRAY               = credentials('bintray.publish')
          GRGIT                 = credentials('github_up')

          BINTRAY_USER          = "${BINTRAY_USR}"
          BINTRAY_API_KEY       = "${BINTRAY_PSW}"
          GRGIT_USER            = "${GRGIT_USR}"
          GRGIT_PASS            = "${GRGIT_PSW}"
          GITHUB_LOGIN          = "${GRGIT_USR}"
          GITHUB_PASSWORD       = "${GRGIT_PSW}"
        }

        steps {
          gradleWrapper "${params.RELEASE_TYPE.trim()} -Pbintray.user=${BINTRAY_USER} -Pbintray.key=${BINTRAY_API_KEY} -Prelease.stage=${params.RELEASE_TYPE.trim()} -Prelease.scope=${params.RELEASE_SCOPE} -x check"
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
