#!/usr/bin/env groovy
import net.wooga.jenkins.pipeline.config.Config

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildLocalGradlePlugin                                                                                        //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map configMap = [:]) {
  //organize configs inside neat object. Defaults are defined there as well
  def config = Config.fromConfigMap(configMap, this)
  def mainPlatform = config.mainPlatform.name
  def projectDirectoryGlob = configMap.projectDirectoryGlob ?: "**"

  pipeline {
    agent none

    options {
      buildDiscarder(logRotator(artifactNumToKeepStr:'40'))
    }

    parameters {
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

      stage("check local plugin") {
        agent any
        when {
          anyOf {
            changeset comparator: 'GLOB', pattern: "$projectDirectoryGlob"
            isRestartedRun()
            triggeredBy 'UserIdCause'
          }
        }

        steps {
          javaLibCheck config: config
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
