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
  configMap.logLevel = configMap.get("logLevel", params.LOG_LEVEL?: env.LOG_LEVEL as String)
  configMap.showStackTrace = configMap.get("showStackTrace", params.STACK_TRACE as Boolean)
  configMap.refreshDependencies = configMap.get("refreshDependencies", params.REFRESH_DEPENDENCIES as Boolean)
  def config = WDKConfig.fromConfigMap("macos", configMap, this)

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
      choice(choices: ["snapshot", "rc", "final"], description: 'Choose the distribution type', name: 'RELEASE_TYPE')
      choice(choices: ["", "patch", "minor", "major"], description: 'Choose the change scope', name: 'RELEASE_SCOPE')
      choice(choices: ["", "quiet", "info", "warn", "debug"], description: 'Choose the log level', name: 'LOG_LEVEL')
      booleanParam(defaultValue: false, description: 'Whether to log truncated stacktraces', name: 'STACK_TRACE')
      booleanParam(defaultValue: false, description: 'Whether to refresh dependencies', name: 'REFRESH_DEPENDENCIES')
    }

    stages {

      stage('setup') {
        agent {
          label "atlas && $config.buildLabel"
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
            cleanWs()
          }
        }
      }

      stage('build') {
        failFast true
        parallel {
          stage('assemble package') {
            agent {
               label "atlas && $config.buildLabel"
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
                def checks = config.pipelineTools.checks.forWDKPipelines()
                def stepsForParallel = checks.wdkCoverage(config.unityVersions,
                        params.RELEASE_TYPE as String, params.RELEASE_SCOPE as String,
                        config.checkArgs, config.conventions)
                  parallel stepsForParallel
              }
            }
          }
        }
      }

      stage('publish') {
        agent {
           label "atlas && $config.buildLabel"
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

            def publisher = config.pipelineTools.createPublishers(params.RELEASE_TYPE, params.RELEASE_SCOPE)
            publisher.unityArtifactoryPaket(unityPath, 'artifactory_publish')
          }
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
