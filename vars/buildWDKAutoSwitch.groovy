#!/usr/bin/env groovy
import net.wooga.jenkins.pipeline.BuildVersion
import net.wooga.jenkins.pipeline.check.steps.Step
import net.wooga.jenkins.pipeline.config.Config

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildWDKAutoSwitch                                                                                            //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map configMap = [ unityVersions:[] ]) {
  def buildLabel = "macos"
  configMap.logLevel = configMap.get("logLevel", params.LOG_LEVEL?: env.LOG_LEVEL as String)
  configMap.showStackTrace = configMap.get("showStackTrace", params.STACK_TRACE as Boolean)
  configMap.refreshDependencies = configMap.get("refreshDependencies", params.REFRESH_DEPENDENCIES as Boolean)
  configMap.clearWs = configMap.get("clearWs", params.CLEAR_WS as boolean)
  def config = configs().unityWDK(buildLabel, configMap, this) as Config
  def mainPlatform = config.platforms[0]

  // We can only configure static pipelines atm.
  // To test multiple unity versions we use a script block with a parallel stages inside.
  pipeline {
    agent none

    options {
      buildDiscarder(logRotator(artifactNumToKeepStr:'40'))
    }

    environment {
      UVM_AUTO_SWITCH_UNITY_EDITOR  = "YES"
      UVM_AUTO_INSTALL_UNITY_EDITOR = "YES"
      LOG_LEVEL = "${config.gradleArgs.logLevel}"
      ATLAS_READ = credentials('artifactory_read') //needed for gradle sto read private packages
    }

    parameters {
      // TODO: Rename RELEASE_TYPE to RELEASE_STAGE
      choice(choices: ["snapshot", "preflight", "rc", "final"], description: 'Choose the distribution type', name: 'RELEASE_TYPE')
      choice(choices: ["", "patch", "minor", "major"], description: 'Choose the change scope', name: 'RELEASE_SCOPE')
      choice(choices: ["", "quiet", "info", "warn", "debug"], description: 'Choose the log level', name: 'LOG_LEVEL')
      booleanParam(defaultValue: false, description: 'Whether to log truncated stacktraces', name: 'STACK_TRACE')
      booleanParam(defaultValue: false, description: 'Whether to refresh dependencies', name: 'REFRESH_DEPENDENCIES')
      booleanParam(defaultValue: false, description: 'Whether to clear workspaces after build', name: 'CLEAR_WS')

    }

    stages {

      stage('setup') {
        agent {
          label "atlas && $buildLabel"
        }

        steps {
          sendSlackNotification "STARTED", true
          script {
            def setup = config.pipelineTools.setups
            setup.wdk(params.RELEASE_TYPE as String, params.RELEASE_SCOPE as String)
          }
        }

        post {
          success {
            stash(name: 'setup_w', useDefaultExcludes: true, includes: "paket.lock, .gradle/**, **/build/**, .paket/**, packages/**, paket-files/**, **/Paket.Unity3D/**, **/Wooga/Plugins/**")
          }

          always {
            archiveArtifacts artifacts: '**/build/logs/*.log', allowEmptyArchive: true
          }

          cleanup {
            script {
              if(mainPlatform.clearWs) {
                cleanWs()
              }
            }
          }
        }
      }

      stage('build') {
        failFast true
        parallel {
          stage('assemble package') {
            agent {
               label "atlas && $buildLabel"
            }

            steps {
              unstash 'setup_w'
              script {
                def assembler = config.pipelineTools.assemblers
                assembler.unityWDK("build", params.RELEASE_TYPE as String, params.RELEASE_SCOPE as String)
              }
            }

            post {
              always {
                stash(name: 'wdk_output', includes: ".gradle/**, **/build/outputs/**/*")
                archiveArtifacts artifacts: 'build/outputs/*.nupkg', allowEmptyArchive: true
                archiveArtifacts artifacts: 'build/outputs/*.unitypackage', allowEmptyArchive: true
                archiveArtifacts artifacts: '**/build/logs/*.log', allowEmptyArchive: true
              }
              cleanup {
                script {
                  if(mainPlatform.clearWs) {
                    cleanWs()
                  }
                }
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
                Step checkTemplate = wdkCheckTemplate(config.pipelineTools.checks, config.checkArgs,
                        params.RELEASE_TYPE as String, params.RELEASE_SCOPE as String, config.conventions)
                def checksForParallel = parallelize(checkTemplate, config.platforms, config.conventions.wdkParallelPrefix)
                parallel checksForParallel
              }
            }
          }
        }
      }

      stage('publish') {
        agent {
           label "atlas && $buildLabel"
        }

        environment {
          GRGIT              = credentials('github_access')
          GRGIT_USER         = "${GRGIT_USR}"
          GRGIT_PASS         = "${GRGIT_PSW}"
          GITHUB_LOGIN       = "${GRGIT_USR}"
          GITHUB_PASSWORD    = "${GRGIT_PSW}"
        }

        steps {
          unstash 'setup_w'
          unstash 'wdk_output'
          script {
            def applicationsHome = env.APPLICATIONS_HOME?: ""
            def unityExecPackagePath = env.UNITY_EXEC_PACKAGE_PATH?: ""
            def mainBuildVersion = BuildVersion.parse(configMap.unityVersions[0]).toLabel()
            def unityDir = "Unity-${mainBuildVersion}"
            def unityPath = "${applicationsHome}/${unityDir}/${unityExecPackagePath}".toString()

            def publisher = config.pipelineTools.createPublishers(params.RELEASE_TYPE, params.RELEASE_SCOPE)
            publisher.unityArtifactoryPaket(unityPath, 'artifactory_publish')
          }
        }

        post {
          always {
            archiveArtifacts artifacts: '**/build/logs/*.log', allowEmptyArchive: true
          }
          cleanup {
            script {
              if(mainPlatform.clearWs) {
                cleanWs()
              }
            }
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
