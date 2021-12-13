#!/usr/bin/env groovy

import net.wooga.jenkins.pipeline.config.JavaConfig

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildJavaLibrary                                                                                              //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map configMap = [:]) {
  configMap.logLevel = configMap.get("logLevel", params.LOG_LEVEL?: env.LOG_LEVEL as String)
  configMap.showStackTrace = configMap.get("showStackTrace", params.STACK_TRACE as Boolean)
  configMap.refreshDependencies = configMap.get("refreshDependencies", params.REFRESH_DEPENDENCIES as Boolean)
  def config = JavaConfig.fromConfigMap(configMap, this)
  def mainPlatform = config.mainPlatform.name

  pipeline {
    agent none

    options {
      buildDiscarder(logRotator(artifactNumToKeepStr:'40'))
    }

    parameters {
      choice(choices: ["snapshot", "rc", "final"], description: 'Choose the distribution type', name: 'RELEASE_TYPE')
      choice(choices: ["patch", "minor", "major"], description: 'Choose the change scope', name: 'RELEASE_SCOPE')
      choice(choices: ["", "quiet", "info", "warn", "debug"], description: 'Choose the log level', name: 'LOG_LEVEL')
      booleanParam(defaultValue: false, description: 'Whether to log truncated stacktraces', name: 'STACK_TRACE')
      booleanParam(defaultValue: false, description: 'Whether to refresh dependencies', name: 'REFRESH_DEPENDENCIES')
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
          expression { return params.RELEASE_TYPE == "snapshot"}
        }
        steps {
          javaLibCheck config: config
        }

        post {
          success {
            script {
              if (config.coverallsToken) {
                httpRequest httpMode: 'POST', ignoreSslErrors: true, validResponseCodes: '100:599', url: "https://coveralls.io/webhook?repo_token=${config.coverallsToken}"
              }
            }
          }
        }
      }

      stage('publish') {
        agent {
          label "$mainPlatform && atlas"
        }

        environment {
          GRGIT                     = credentials('github_access')
          GRGIT_USER                = "${GRGIT_USR}"
          GRGIT_PASS                = "${GRGIT_PSW}"
          GITHUB_LOGIN              = "${GRGIT_USR}"
          GITHUB_PASSWORD           = "${GRGIT_PSW}"
        }

        steps {
          publish(params.RELEASE_TYPE, params.RELEASE_SCOPE) {
            artifactoryOSSRH('artifactory_publish',
                              'ossrh.signing.key',
                              'ossrh.signing.key_id',
                              'ossrh.signing.passphrase')
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
