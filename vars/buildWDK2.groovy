#!/usr/bin/env groovy

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildWDK2                                                                                                     //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

import com.wooga.jenkins.UnityTestVersionSpecResolver
import com.wooga.jenkins.UnityTestVersionSpec

def call(Map config = [unityVersions:[]]) {
  // println("start buildWDK2")
  def unityVersionTestSpecs = [new UnityTestVersionSpec("2017.3"), new UnityTestVersionSpec("2017.3.1"), new UnityTestVersionSpec("2017.4"), new UnityTestVersionSpec("2018.3")]
  echo("run build with unity test versions requirements:")
  echo("${unityVersionTestSpecs.isEmpty()}")

  for(UnityTestVersionSpec spec in unityVersionTestSpecs) {
    echo("-[${spec.versionReq}]: optional: ${spec.optional} releaseType: ${spec.releaseType}")
  }

  echo("resolve versions:")
  def resolver = new UnityTestVersionSpecResolver(unityVersionTestSpecs)
  unityVersionTestSpecs = resolver.resolveVersions()
  echo("${unityVersionTestSpecs}")
  //echo("${newVersions.isEmpty()}")
  echo("run build with unity test versions:")
  for(UnityTestVersionSpec spec in unityVersionTestSpecs) {
    echo("-[${spec.versionReq}]: optional: ${spec.optional}")
  }

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
               label "atlas && primary"
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
                println(unityVersionTestSpecs)
                def stepsForParallel = unityVersionTestSpecs.collectEntries { spec ->
                  ["check Unity-${spec.versionReq}" : transformIntoCheckStep(spec)]
                }
                parallel stepsForParallel
              }
            }
          }
        }
      }

      stage('publish') {
        agent {
           label "atlas && primary"
        }

        environment {
          GRGIT              = credentials('github_up')
          GRGIT_USER         = "${GRGIT_USR}"
          GRGIT_PASS         = "${GRGIT_PSW}"
          GITHUB_LOGIN       = "${GRGIT_USR}"
          GITHUB_PASSWORD    = "${GRGIT_PSW}"
          NUGET_KEY          = credentials('artifactory_publish')
          nugetkey           = "${NUGET_KEY}"
          UNITY_PATH         = "${APPLICATIONS_HOME}/Unity-${unityVersionTestSpecs[0]}/${UNITY_EXEC_PACKAGE_PATH}"
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
def transformIntoCheckStep(UnityTestVersionSpec versionSpec) {
  return {
    node("atlas && primary") {
      try {
        checkout scm
        withEnv(["UVM_UNITY_VERSION=${versionSpec.versionReq}", "UNITY_LOG_CATEGORY=check-${versionSpec.versionReq}"]) {
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
