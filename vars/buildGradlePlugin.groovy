#!/usr/bin/env groovy

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildGradlePlugin                                                                                             //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map config = [plaforms:['osx','windows'], testEnvironment:[], coverallsToken:null]) {
  def plaforms = config.plaforms
  def mainPlatform = plaforms[0]

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
            def stepsForParallel = plaforms.collectEntries {
              ["check ${it}" : transformIntoCheckStep(it, config.testEnvironment, config.coverallsToken)]
            }

            parallel stepsForParallel
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
        }

        steps {
          gradleWrapper "${params.RELEASE_TYPE.trim()} -P.gradle.publish.key=${GRADLE_PUBLISH_KEY} -P.gradle.publish.secret=${GRADLE_PUBLISH_SECRET} -Prelease.stage=${params.RELEASE_TYPE.trim()} -Prelease.scope=${params.RELEASE_SCOPE} -x check"
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
        if(!currentBuild.result) {
          def command = (coverallsToken) ? "jacocoTestReport coveralls" : "jacocoTestReport"
          withEnv(["COVERALLS_REPO_TOKEN=${coverallsToken}"]) {
              gradleWrapper command
              publishHTML([
                          allowMissing: true,
                          alwaysLinkToLastBuild: true,
                          keepAll: true,
                          reportDir: 'build/reports/jacoco/test/html',
                          reportFiles: 'index.html',
                          reportName: 'Coverage',
                          reportTitles: ''
                          ])
          }
        }

        junit allowEmptyResults: true, testResults: 'build/test-results/**/*.xml'
      }
    }
  }
}
