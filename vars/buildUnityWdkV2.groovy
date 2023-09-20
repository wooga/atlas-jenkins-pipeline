#!/usr/bin/env groovy
import net.wooga.jenkins.pipeline.config.PipelineConventions
import net.wooga.jenkins.pipeline.config.Platform
import net.wooga.jenkins.pipeline.config.WDKConfig

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildUnityWdkV2                                                                                            //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map configMap = [unityVersions: []]) {
  configMap.testLabels = ["macOS-agent-14"]
  configMap.logLevel = configMap.get("logLevel", params.LOG_LEVEL ?: env.LOG_LEVEL as String)
  configMap.showStackTrace = configMap.get("showStackTrace", params.STACK_TRACE as Boolean)
  configMap.refreshDependencies = configMap.get("refreshDependencies", params.REFRESH_DEPENDENCIES as Boolean)
  configMap.testWrapper = { Closure testOperation, Platform plat ->
    if(env."UNITY_PACKAGE_MANAGER" == "upm") {
      withCredentials([file(credentialsId: 'atlas-upm-credentials', variable: "UPM_USER_CONFIG_FILE")]) {
        testOperation(plat)
      }
    } else {
      testOperation(plat)
    }

  }

  def config = WDKConfig.fromConfigMap("macOS-agent-14", configMap, this)
  def packageManagerEnvVar = "UNITY_PACKAGE_MANAGER"

  // We can only configure static pipelines atm.
  // To test multiple unity versions we use a script block with a parallel stages inside.
  pipeline {
    agent none

    options {
      buildDiscarder(logRotator(artifactNumToKeepStr: '40'))
    }

    environment {
      UVM_AUTO_SWITCH_UNITY_EDITOR = "YES"
      UVM_AUTO_INSTALL_UNITY_EDITOR = "YES"
      LOG_LEVEL = "${config.gradleArgs.logLevel}"
      ATLAS_READ = credentials('artifactory_read') //needed for gradle sto read private packages
    }

    parameters {
      choice(name: 'RELEASE_STAGE', choices: ["snapshot", "preflight", "rc", "final"], description: 'Choose the distribution type')
      choice(name: 'RELEASE_SCOPE', choices: ["", "patch", "minor", "major"], description: 'Choose the scope (semver2)')
      choice(name: 'LOG_LEVEL', choices: ["info", "quiet", "warn", "debug"], description: 'Choose the log level')
      choice(name: 'UPM_RESOLUTION_STRATEGY', choices: ["", "lowest", "highestPatch", "highestMinor", "highest"], description: 'Override the resolution strategy for indirect dependencies')
      booleanParam(name: 'STACK_TRACE', defaultValue: false, description: 'Whether to log truncated stacktraces')
      booleanParam(name: 'REFRESH_DEPENDENCIES', defaultValue: false, description: 'Whether to refresh dependencies')
    }

    stages {
      stage("Setup") {
        failFast true
        parallel {
          stage("setup paket") {
            agent {
              label "atlas && $config.buildLabel"
            }
            environment {
              UNITY_PACKAGE_MANAGER = 'paket'
            }
            steps {
              script {
                def setup = config.pipelineTools.setups
                setup.wdk(params.RELEASE_STAGE as String, params.RELEASE_SCOPE as String)
              }
            }
            post {
              success {
                stash(name: 'paket_setup_w', useDefaultExcludes: true, includes: "paket.lock, .gradle/**, **/build/**, .paket/**, packages/**, paket-files/**, **/Paket.Unity3D/**, **/Wooga/Plugins/**")
              }

              always {
                archiveArtifacts artifacts: 'paket.lock, **/build/logs/*.log', allowEmptyArchive: true
              }

              cleanup {
                cleanWs()
              }
            }
          }

          stage("setup upm") {
            agent {
              label "atlas && $config.buildLabel"
            }
            environment {
              UPM_USER_CONFIG_FILE = credentials('atlas-upm-credentials')
              UNITY_PACKAGE_MANAGER = 'upm'
            }
            steps {
              script {
                def setup = config.pipelineTools.setups
                setup.wdk(params.RELEASE_STAGE as String, params.RELEASE_SCOPE as String)
              }
            }
            post {
              success {
                archiveArtifacts artifacts: '**/Packages/packages-lock.json'
                stash(name: 'upm_setup_w', useDefaultExcludes: true, includes: "**/Packages/packages-lock.json, " +
                        "**/PackageCache/**, " +
                        "**/build/**")
              }

              always {
                archiveArtifacts artifacts: '**/build/logs/*.log', allowEmptyArchive: true
              }
              cleanup {
                cleanWs()
              }
            }
          }
        }
      }

      stage("Validate package resolution") {
        agent {
          label "atlas && $config.buildLabel"
        }
        steps {
          unstash 'upm_setup_w'
          unstash 'paket_setup_w'
          catchError(buildResult: "SUCCESS", stageResult: "UNSTABLE", message: "Some creative text") {
            gradleWrapper "validatePackages -PunityPackages.reportsDirectory=$WORKSPACE/build/reports/packages"
          }
        }
        post {
          always {
            publishHTML([allowMissing: false,
                         alwaysLinkToLastBuild: true,
                         keepAll: true,
                         reportDir: 'build/reports/packages/html',
                         reportFiles: 'index.html',
                         reportName: 'Package Resolution',
                         reportTitles: ''])
            archiveArtifacts artifacts: 'build/reports/packages/**/*'
          }
        }
      }

      stage("Build") {
        failFast true
        parallel {

          stage('assemble package') {
            agent {
              label "atlas && $config.buildLabel"
            }
            environment {
              UNITY_PACKAGE_MANAGER = 'upm'
              UPM_USER_CONFIG_FILE = credentials('atlas-upm-credentials')
            }
            steps {
              unstash 'upm_setup_w'
              script {
                def assembler = config.pipelineTools.assemblers
                assembler.unityWDK("build", params.RELEASE_STAGE as String, params.RELEASE_SCOPE as String)
              }
            }

            post {
              always {
                stash(name: 'wdk_output', includes: ".gradle/**, **/build/outputs/**/*")
                archiveArtifacts artifacts: 'build/outputs/*.nupkg', allowEmptyArchive: true
                archiveArtifacts artifacts: '**/build/distributions/*.tgz', allowEmptyArchive: true
                archiveArtifacts artifacts: 'build/outputs/*.unitypackage', allowEmptyArchive: true
                archiveArtifacts artifacts: '**/build/logs/*.log', allowEmptyArchive: true
              }
              cleanup {
                cleanWs()
              }
            }
          }

          stage("Check") {
            failFast true
            when {
              beforeAgent true
              expression {
                return params.RELEASE_STAGE == "snapshot"
              }
            }
            steps {
              script {
                parallel paket:{
                  withEnv(["UNITY_PACKAGE_MANAGER=paket"]) {
                    parallel checkSteps(config, "paket check unity ", "paket_setup_w")
                  }
                },
                upm:{
                  withEnv(["UNITY_PACKAGE_MANAGER=upm"]) {
                    parallel checkSteps(config, "upm check unity ", "upm_setup_w")
                  }
                },
                failFast : true
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
          UNITY_PACKAGE_MANAGER = 'upm'
          GRGIT = credentials('github_access')
          UPM_USER_CONFIG_FILE = credentials('atlas-upm-credentials')
          GRGIT_USER = "${GRGIT_USR}"
          GRGIT_PASS = "${GRGIT_PSW}"
          GITHUB_LOGIN = "${GRGIT_USR}"
          GITHUB_PASSWORD = "${GRGIT_PSW}"
        }

        steps {
          unstash 'upm_setup_w'
          unstash 'wdk_output'
          script {
            def publisher = config.pipelineTools.createPublishers(params.RELEASE_STAGE, params.RELEASE_SCOPE)
            publisher.unityArtifactoryUpm('artifactory_publish')
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

def checkSteps(WDKConfig config, String parallelChecksPrefix, String setupStashId) {
  def conventions = new PipelineConventions(config.conventions)
  conventions.wdkParallelPrefix = parallelChecksPrefix
  conventions.wdkSetupStashId = setupStashId
  def checks = config.pipelineTools.checks.forWDKPipelines()
  def stepsForParallel = checks.wdkCoverage(config.unityVersions,
          params.RELEASE_STAGE as String, params.RELEASE_SCOPE as String,
          config.checkArgs, conventions)
  return stepsForParallel
}
