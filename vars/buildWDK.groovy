#!/usr/bin/env groovy

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildWDK                                                                                                      //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map config = [unityVersions:[]]) {
  def unityVersions = config.unityVersions
  def mainVersionLabel = "unity_${unityVersions[0]}e"

  //we need at least one valid unity version for now.
  if(unityVersions.size == 0) {
    error "Please provide at least one unity version."
  }

  //Check if all unity versions are supported
  for(version in unityVersions) {
    def label = "unity_${version}e"
    def nodes = nodesByLabel(label: label)
    if(nodes.size == 0) {
      error "No execution node available with unity versions ${version}."
    }
  }

  //We can only configure static pipelines atm.
  //To test multiple unity versions we use a script block with a parallel stages inside.

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
      stage('setup') {

        agent {
          label "secondary && atlas"
        }

        steps {
          sendSlackNotification "STARTED", true
          gradleWrapper "-Prelease.stage=${params.RELEASE_TYPE.trim()} -Prelease.scope=$params.RELEASE_SCOPE setup"
        }

        post {
          success {
            stash(name: 'setup_w', useDefaultExcludes: true, includes: ".gradle/**, **/build/**, .paket/**, packages/**, paket-files/**, **/Paket.Unity3D/**, **/Wooga/Plugins/**")
          }

          always {
            archiveArtifacts artifacts: '**/build/logs/*.log', allowEmptyArchive: true
          }
        }
      }

      stage('build') {
        failFast true
        parallel {
          stage('assemble package') {
            agent {
               label "$mainVersionLabel && atlas && primary"
            }

            environment {
              UNITY_PATH         = "${APPLICATIONS_HOME}/Unity-${unityVersions[0]}/${UNITY_EXEC_PACKAGE_PATH}"
              UNITY_LOG_CATEGORY = "build"
            }

            steps {
              unstash 'setup_w'
              gradleWrapper "-Prelease.stage=${params.RELEASE_TYPE.trim()} -Prelease.scope=${params.RELEASE_SCOPE} assemble"
            }

            post {
              success {
                stash(name: 'wdk_output', includes: ".gradle/**, **/build/outputs/**/*")
              }

              always {
                archiveArtifacts artifacts: 'build/outputs/*.nupkg', allowEmptyArchive: true
                archiveArtifacts artifacts: 'build/outputs/*.unitypackage', allowEmptyArchive: true
                archiveArtifacts artifacts: '**/build/logs/*.log', allowEmptyArchive: true
              }
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
                def stepsForParallel = unityVersions.collectEntries {
                  ["check Unity-${it}" : transformIntoCheckStep(it)]
                }
                parallel stepsForParallel
              }
            }
          }
        }
      }

      stage('publish') {
        agent {
           label "$mainVersionLabel && atlas && primary"
        }

        environment {
          GRGIT              = credentials('github_up')
          GRGIT_USER         = "${GRGIT_USR}"
          GRGIT_PASS         = "${GRGIT_PSW}"
          GITHUB_LOGIN       = "${GRGIT_USR}"
          GITHUB_PASSWORD    = "${GRGIT_PSW}"
          NUGET_KEY          = credentials('artifactory_deploy')
          nugetkey           = "${NUGET_KEY}"
          UNITY_PATH         = "${APPLICATIONS_HOME}/Unity-${unityVersions[0]}/${UNITY_EXEC_PACKAGE_PATH}"
          UNITY_LOG_CATEGORY = "build"
        }

        steps {
          unstash 'setup_w'
          unstash 'wdk_output'
          gradleWrapper "${params.RELEASE_TYPE.trim()} -Prelease.stage=${params.RELEASE_TYPE.trim()} -Ppaket.publish.repository='$params.RELEASE_TYPE' -Prelease.scope=${params.RELEASE_SCOPE} -x check"
        }

        post {
          always {
            archiveArtifacts artifacts: '**/build/logs/*.log', allowEmptyArchive: true
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
def transformIntoCheckStep(version) {
  return {
    node("unity_${version}e && atlas && primary") {
      try {
        checkout scm
        withEnv(["UNITY_PATH=${APPLICATIONS_HOME}/Unity-${version}/${UNITY_EXEC_PACKAGE_PATH}", "UNITY_LOG_CATEGORY=check-${version}"]) {
          unstash 'setup_w'
          gradleWrapper "-Prelease.stage=${params.RELEASE_TYPE.trim()} -Prelease.scope=$params.RELEASE_SCOPE check"
        }
      }
      finally {
        nunit failIfNoResults: false, testResultsPattern: '**/build/reports/unity/**/*.xml'
        archiveArtifacts artifacts: '**/build/logs/*.log', allowEmptyArchive: true
        archiveArtifacts artifacts: '**/build/reports/unity/**/*.xml' , allowEmptyArchive: true
      }
    }
  }
}
