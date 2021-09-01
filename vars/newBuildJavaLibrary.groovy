#!/usr/bin/env groovy
import net.wooga.jenkins.pipeline.config.Config
import net.wooga.jenkins.pipeline.TestHelper

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildJavaLibrary                                                                                              //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map configMap = [:]) {
  //set config defaults
  Config config = Config.fromConfigMap(configMap, this.binding.variables)

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
                def stepsForParallel = javaLibTest(config).checkStepsWithCoverage(params.RUN_SONARQUBE)
                parallel stepsForParallel
              }
            }
        }

        post {
          success {
            httpRequest httpMode: 'POST', ignoreSslErrors: true, url: "https://coveralls.io/webhook?repo_token=${config.coverallsToken}"
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
