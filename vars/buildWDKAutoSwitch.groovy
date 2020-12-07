#!/usr/bin/env groovy

import net.wooga.jenkins.pipeline.TestHelper

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildWDKAutoSwitch                                                                                            //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map config = [unityVersions:[]]) {
  def unityVersions = config.unityVersions
  config.testEnvironment = config.testEnvironment ?: []
  config.testLabels = config.testLabels ?: []
  config.labels = config.labels ?: ''
  //we need at least one valid unity version for now.
  if(unityVersions.isEmpty()) {
    error "Please provide at least one unity version."
  }

  def helper = new TestHelper()

  //We can only configure static pipelines atm.
  //To test multiple unity versions we use a script block with a parallel stages inside.
  pipeline {
    agent none

    options {
      buildDiscarder(logRotator(artifactNumToKeepStr:'40'))
    }

    environment {
      UVM_AUTO_SWITCH_UNITY_EDITOR  = "YES"
      UVM_AUTO_INSTALL_UNITY_EDITOR = "YES"
      ATLAS_READ = credentials('artifactory_read')
    }

    parameters {
        choice(choices: "snapshot\nrc\nfinal", description: 'Choose the distribution type', name: 'RELEASE_TYPE')
        choice(choices: "\npatch\nminor\nmajor", description: 'Choose the change scope', name: 'RELEASE_SCOPE')
    }

    stages {
      stage('setup') {

        agent {
          label "secondary && atlas && macos"
        }

        steps {
          sendSlackNotification "STARTED", true
          gradleWrapper "-Prelease.stage=${params.RELEASE_TYPE.trim()} -Prelease.scope=$params.RELEASE_SCOPE setup"
        }

        post {
          success {
            stash(name: 'setup_w', useDefaultExcludes: true, includes: "paket.lock .gradle/**, **/build/**, .paket/**, packages/**, paket-files/**, **/Paket.Unity3D/**, **/Wooga/Plugins/**")
          }

          always {
            archiveArtifacts artifacts: '**/build/logs/*.log', allowEmptyArchive: true
          }

          cleanup {
            cleanWs()
          }
        }
      }

      stage('build') {
        failFast true
        parallel {
          stage('assemble package') {
            agent {
               label "atlas && primary && macos"
            }

            environment {
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

              cleanup {
                cleanWs()
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
                def stepsForParallel = unityVersions.collectEntries { version ->
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

                  environment.addAll(["UVM_UNITY_VERSION=${version}", "UNITY_LOG_CATEGORY=check-${version}"])

                  def checkStep = {
                    dir (version) {
                      checkout scm
                      unstash 'setup_w'
                      gradleWrapper "-Prelease.stage=${params.RELEASE_TYPE.trim()} -Prelease.scope=$params.RELEASE_SCOPE check"
                    }
                  }

                  def finalizeStep = {
                    nunit failIfNoResults: false, testResultsPattern: '**/build/reports/unity/**/*.xml'
                    archiveArtifacts artifacts: '**/build/logs/**/*.log', allowEmptyArchive: true
                    archiveArtifacts artifacts: '**/build/reports/unity/**/*.xml' , allowEmptyArchive: true
                    archiveArtifacts artifacts: '**/build/codeCoverage/**/*.xml' , allowEmptyArchive: true
                    publishCoverage adapters: [istanbulCoberturaAdapter('**/codeCoverage/Cobertura.xml')], sourceFileResolver: sourceFiles('NEVER_STORE')
                    dir (version) {
                      deleteDir()
                    }
                    cleanWs()
                  }

                  ["check Unity-${version}" : helper.transformIntoCheckStep("macos", environment, null, testConfig, checkStep, finalizeStep, true)]
                }
                parallel stepsForParallel
              }
            }
          }
        }
      }

      stage('publish') {
        agent {
           label "atlas && primary && macos"
        }

        environment {
          GRGIT              = credentials('github_up')
          GRGIT_USER         = "${GRGIT_USR}"
          GRGIT_PASS         = "${GRGIT_PSW}"
          GITHUB_LOGIN       = "${GRGIT_USR}"
          GITHUB_PASSWORD    = "${GRGIT_PSW}"
          NUGET_KEY          = credentials('artifactory_publish')
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
