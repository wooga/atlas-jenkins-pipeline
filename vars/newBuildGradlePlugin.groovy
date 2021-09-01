#!/usr/bin/env groovy

import net.wooga.jenkins.pipeline.config.Config
import net.wooga.jenkins.pipeline.TestHelper

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildGradlePlugin                                                                                             //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map configMap = [:]) {
  //organize configs inside neat object. Defaults are defined there as well
  Config config = Config.fromConfigMap(configMap, this.binding.variables)
  def mainPlatform = config.platforms[0].name
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
      booleanParam(defaultValue: false, description: 'Whether to force sonarqube execution', name: 'RUN_SONARQUBE')
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
            withEnv(["COVERALLS_PARALLEL=true"]) {
              def stepsForParallel = javaLibCheck(config).
                                      checkStepsWithCoverage(params.RUN_SONARQUBE)
              parallel stepsForParallel
            }
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
          gradleWrapper "${params.RELEASE_TYPE.trim()} -Pgradle.publish.key=${env.GRADLE_PUBLISH_KEY} -Pgradle.publish.secret=${env.GRADLE_PUBLISH_SECRET} -Prelease.stage=${params.RELEASE_TYPE.trim()} -Prelease.scope=${params.RELEASE_SCOPE} -x check"
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