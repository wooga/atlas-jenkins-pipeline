#!/usr/bin/env groovy

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildJavaLibrary                                                                                              //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map config = [plaforms:['osx','windows'], testEnvironment:[], coverallsToken:null, labels: '']) {
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
              def environment = []
              if(config.testEnvironment) {
                if(config.testEnvironment instanceof List) {
                  environment = config.testEnvironment
                }
                else {
                  environment = (config.testEnvironment[it]) ?: []
                }
              }

              ["check ${it}" : transformIntoCheckStep(it, environment, config.coverallsToken, config)]
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
def transformIntoCheckStep(platform, testEnvironment, coverallsToken, config) {
  return {
    def node_label = "${platform} && atlas"

    if(config.labels) {
      node_label += "&& ${config.labels}"
    }

    node(node_label) {
      try {
        testEnvironment = testEnvironment.collect { item ->
          if(item instanceof groovy.lang.Closure) {
            return item.call().toString()
          }

          return item.toString()
        }

        checkout scm
        withEnv(["TRAVIS_JOB_NUMBER=${BUILD_NUMBER}.${platform.toUpperCase()}"]) {
          withEnv(testEnvironment) {
            gradleWrapper "check"
          }
        }
      }
      catch(Exception error) {
        println(error)
        println(error.printStackTrace())
        currentBuild.result = 'FAILURE'
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

        junit allowEmptyResults: true, testResults: '**/build/test-results/**/*.xml'
      }
    }
  }
}
