#!/usr/bin/env groovy

import net.wooga.jenkins.pipeline.config.Config

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildJavaLibraryOSSRH                                                                                         //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map configMap = [:]) {
  Config config = Config.fromConfigMap(configMap, this)
  def mainPlatform = config.platforms[0].name

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
          javaLibCheck config: config
        }

        post {
          cleanup {
            cleanWs()
          }
          success {
            script {
              if(config.coverallsToken) {
                httpRequest httpMode: 'POST', ignoreSslErrors: true, validResponseCodes: '100:599', url: "https://coveralls.io/webhook?repo_token=${config.coverallsToken}"
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
          GRGIT = credentials('github_up')
          GRGIT_USER = "${GRGIT_USR}"
          GRGIT_PASS = "${GRGIT_PSW}"
          GITHUB_LOGIN = "${GRGIT_USR}"
          GITHUB_PASSWORD = "${GRGIT_PSW}"
        }

        steps {
          publish(params.RELEASE_TYPE, params.RELEASE_SCOPE) {
            ossrh('ossrh.publish', 'ossrh.signing.key', 'ossrh.signing.key_id', 'ossrh.signing.passphrase')
          }
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
