#!/usr/bin/env groovy
import net.wooga.jenkins.pipeline.assemble.Assemblers
import net.wooga.jenkins.pipeline.check.Checks
import net.wooga.jenkins.pipeline.config.WDKConfig
import net.wooga.jenkins.pipeline.model.Gradle
import net.wooga.jenkins.pipeline.setup.Setups

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildWDKAutoSwitch                                                                                            //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map configMap = [ unityVersions:[] ]) {
  def defaultReleaseType = "snapshot"
  def defaultReleaseScope = ""

  configMap.logLevel = configMap.get("logLevel", params.LOG_LEVEL?: env.LOG_LEVEL as String)
  configMap.showStackTrace = configMap.get("showStackTrace", params.STACK_TRACE as Boolean)
  configMap.refreshDependencies = configMap.get("refreshDependencies", params.REFRESH_DEPENDENCIES as Boolean)
  configMap.clearWs = configMap.get("clearWs", params.CLEAR_WS as boolean)
  def config = WDKConfig.fromConfigMap(configMap, this)
  def mainPlatform = config.unityVersions[0].platform

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
      choice(choices: [defaultReleaseType, "preflight", "rc", "final"], description: 'Choose the distribution type', name: 'RELEASE_TYPE')
      choice(choices: [defaultReleaseScope, "patch", "minor", "major"], description: 'Choose the change scope', name: 'RELEASE_SCOPE')
      choice(choices: ["", "quiet", "info", "warn", "debug"], description: 'Choose the log level', name: 'LOG_LEVEL')
      booleanParam(defaultValue: false, description: 'Whether to log truncated stacktraces', name: 'STACK_TRACE')
      booleanParam(defaultValue: false, description: 'Whether to refresh dependencies', name: 'REFRESH_DEPENDENCIES')
      booleanParam(defaultValue: false, description: 'Whether to clear workspaces after build', name: 'CLEAR_WS')

    }

    stages {

      stage('setup') {
        agent {
          label "atlas && macos"
        }

        steps {
          sendSlackNotification "STARTED", true
          script {
            env.RELEASE_TYPE = params.RELEASE_TYPE?: defaultReleaseType
            env.RELEASE_SCOPE = params.RELEASE_SCOPE?: defaultReleaseScope
            def setup = config.pipelineTools.setups
            setup.wdk(env.RELEASE_TYPE as String, env.RELEASE_SCOPE as String)
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
          stage('assemble package macos') {
            agent {
               label "atlas && macos"
            }

            steps {
              unstash 'setup_w'
              script {
                def assembler = config.pipelineTools.assemblers
                def nupkgFile = assembler.unityWDK("build", env.RELEASE_TYPE as String, env.RELEASE_SCOPE as String)
                sh script: "cp ${nupkgFile?.path} nupkg-macos.nupkg"
                stash(name: "macos_wdk_nupkg", includes: "nupkg-macos.nupkg")
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

          stage('assemble package linux') {
            agent {
              label "atlas && linux"
            }

            steps {
              unstash 'setup_w'
              script {
                catchError(buildResult: "SUCCESS", stageResult: "UNSTABLE") {
                  def assembler = config.pipelineTools.assemblers
                  def nupkgFile = assembler.unityWDK("build", params.RELEASE_TYPE as String, params.RELEASE_SCOPE as String)
                  sh script: "cp ${nupkgFile?.path} nupkg-linux.nupkg"
                  stash(name: "linux_wdk_nupkg", includes: "nupkg-linux.nupkg")
                }
              }
            }

            post {
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
                return env.RELEASE_TYPE == "snapshot"
              }
            }

            steps {
              script {
                def checks = config.pipelineTools.checks.forWDKPipelines()
                def stepsForParallel = checks.wdkCoverage(config.unityVersions,
                        env.RELEASE_TYPE as String, env.RELEASE_SCOPE as String,
                        config.checkArgs, config.conventions)
                  parallel stepsForParallel
              }
            }
          }
        }
      }

      stage("compare hashes") {
        agent {
          label "atlas && macos"
        }
        steps {
          script {
            catchError(buildResult: "SUCCESS", stageResult: "UNSTABLE") {
              def zip_sha256 = { file ->
                def dirName = "${file}_unzipped".toString()
                unzip zipFile: file, dir: dirName
                return sh(returnStdout: true, script: "find $dirName -type f | xargs cat | shasum -a 256 | awk '{print \$1}'").trim()
              }

              unstash "macos_wdk_nupkg"
              def macos_hash = zip_sha256("nupkg-macos.nupkg")
              echo "macos hash: $macos_hash"

              unstash "linux_wdk_nupkg"
              def linux_hash = zip_sha256("nupkg-linux.nupkg")
              echo "linux hash: $linux_hash"

              if (linux_hash != macos_hash) {
                throw new Exception("Hashes are not equal: $linux_hash != $macos_hash")
              }
            }
          }
        }
      }

      stage('publish') {
        agent {
           label "atlas && macos"
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
            def unityPath = "${applicationsHome}/${config.unityVersions[0].stepDescription}/${unityExecPackagePath}"

            def publisher = config.pipelineTools.createPublishers(env.RELEASE_TYPE, env.RELEASE_SCOPE)
            publisher.unityArtifactoryPaket(unityPath, env.RELEASE_STAGE == defaultReleaseType ? "artifactory_publish": "artifactory_deploy")
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
