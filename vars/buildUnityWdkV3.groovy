#!/usr/bin/env groovy
import net.wooga.jenkins.pipeline.check.steps.Step
import net.wooga.jenkins.pipeline.config.PipelineConventions
import net.wooga.jenkins.pipeline.config.Platform
import net.wooga.jenkins.pipeline.config.WDKConfig

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildUnityWdkV3                                                                                            //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map configMap = [unityVersions: []]) {
  def defaultReleaseType = "snapshot"
  def defaultReleaseScope = ""

  configMap.logLevel = configMap.get("logLevel", params.LOG_LEVEL ?: env.LOG_LEVEL as String)
  configMap.showStackTrace = configMap.get("showStackTrace", params.STACK_TRACE as Boolean)
  configMap.refreshDependencies = configMap.get("refreshDependencies", params.REFRESH_DEPENDENCIES as Boolean)
  configMap.clearWs = configMap.get("clearWs", params.CLEAR_WS as boolean)
  configMap.packageType = configMap.get("packageType", 'any')
  configMap.testWrapper = { Step testOperation, Platform plat ->
    if(env."UNITY_PACKAGE_MANAGER" == "upm") {
      withCredentials([file(credentialsId: 'atlas-upm-credentials', variable: "UPM_USER_CONFIG_FILE")]) {
        testOperation(plat)
      }
    } else {
      testOperation(plat)
    }
  }
  def config = WDKConfig.fromConfigMap(configMap, this)
  def hasToBuildUpm = config.unityVersions.any({ version
    -> version.packageType == "any" || version.packageType == "upm" })
  def hasToBuildPaket = config.unityVersions.any({ version
    -> version.packageType == "any" || version.packageType == "paket" })

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
      choice(name: 'RELEASE_STAGE', choices: [defaultReleaseType, "preflight", "rc", "final"], description: 'Choose the distribution type')
      choice(name: 'RELEASE_SCOPE', choices: [defaultReleaseScope, "patch", "minor", "major"], description: 'Choose the scope (semver2)')
      choice(name: 'LOG_LEVEL', choices: ["info", "quiet", "warn", "debug"], description: 'Choose the log level')
      choice(name: 'UPM_RESOLUTION_STRATEGY', choices: ["", "lowest", "highestPatch", "highestMinor", "highest"], description: 'Override the resolution strategy for indirect dependencies')
      booleanParam(name: 'STACK_TRACE', defaultValue: false, description: 'Whether to log truncated stacktraces')
      booleanParam(name: 'REFRESH_DEPENDENCIES', defaultValue: false, description: 'Whether to refresh dependencies')
      booleanParam(name: 'CLEAR_WS', defaultValue: false, description: 'Whether to clear workspaces after build')
    }

    stages {
      stage("Setup") {
        failFast true
        parallel {
          stage("setup paket") {
            when {
              expression {
                hasToBuildPaket
              }
            }
            agent {
              label "atlas && macos"
            }
            environment {
              UNITY_PACKAGE_MANAGER = 'paket'
            }
            steps {
              script {
                env.RELEASE_STAGE = params.RELEASE_STAGE?: defaultReleaseType
                env.RELEASE_SCOPE = params.RELEASE_SCOPE?: defaultReleaseScope
                def setup = config.pipelineTools.setups
                setup.wdk(env.RELEASE_STAGE as String, env.RELEASE_SCOPE as String)
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
                script {
                  if(config.mainPlatform.clearWs) {
                    cleanWs()
                  }
                }
              }
            }
          }

          stage("setup upm") {
            when{
              expression{
                hasToBuildUpm
              }
            }
            agent {
              label "atlas && macos"
            }
            environment {
              UPM_USER_CONFIG_FILE = credentials('atlas-upm-credentials')
              UNITY_PACKAGE_MANAGER = 'upm'
            }
            steps {
              script {
                env.RELEASE_STAGE = params.RELEASE_STAGE?: defaultReleaseType
                env.RELEASE_SCOPE = params.RELEASE_SCOPE?: defaultReleaseScope
                def setup = config.pipelineTools.setups
                setup.wdk(env.RELEASE_STAGE as String, env.RELEASE_SCOPE as String)
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
                script {
                  if(config.mainPlatform.clearWs) {
                    cleanWs()
                  }
                }
              }
            }
          }
        }
      }

      stage("Validate package resolution") {
        agent {
          label "atlas && macos"
        }
        steps {
          script {
            if (hasToBuildUpm) {
              unstash 'upm_setup_w'
            }
            if (hasToBuildPaket) {
              unstash 'paket_setup_w'
            }
          }
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
            when{
              expression{
                hasToBuildUpm
              }
            }
            agent {
              label "atlas && macos"
            }
            environment {
              UNITY_PACKAGE_MANAGER = 'upm'
              UPM_USER_CONFIG_FILE = credentials('atlas-upm-credentials')
            }
            steps {
              unstash 'upm_setup_w'
              script {
                def assembler = config.pipelineTools.assemblers
                assembler.unityWDK("build", env.RELEASE_STAGE as String, env.RELEASE_SCOPE as String)
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
                script {
                  if(config.mainPlatform.clearWs) {
                    cleanWs()
                  }
                }
              }
            }
          }

          stage("Check") {
            failFast true
            when {
              beforeAgent true
              expression {
                return env.RELEASE_STAGE == "snapshot"
              }
            }
            steps {
              script {
                parallel paket: {
                  when {
                    expression{
                      hasToBuildPaket
                    }
                  }
                  withEnv(["UNITY_PACKAGE_MANAGER=paket"]) {
                    parallel checkSteps(config, "paket check unity ", "paket_setup_w")
                  }
                },
                upm: {
                  when {
                    expression{
                      hasToBuildUpm
                    }
                  }
                  withEnv(["UNITY_PACKAGE_MANAGER=upm"]) {
                    parallel checkSteps(config, "upm check unity ", "upm_setup_w")
                  }
                }
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
          GRGIT = credentials('github_access')
          UPM_USER_CONFIG_FILE = credentials('atlas-upm-credentials')
          GRGIT_USER = "${GRGIT_USR}"
          GRGIT_PASS = "${GRGIT_PSW}"
          GITHUB_LOGIN = "${GRGIT_USR}"
          GITHUB_PASSWORD = "${GRGIT_PSW}"
        }

        steps {
          script {
            if (hasToBuildUpm) {
              unstash 'upm_setup_w'
            }
            if (hasToBuildPaket) {
              unstash 'paket_setup_w'
            }
            // Some packages are shipped with the paket.template, because of it, paket plugin tries to publish those packages.
            // This is a hack until we implement a better solution (ex: remove the paket.template files when doing paketUnityInstall)
            sh script: "find Packages -type f -iname \"paket.template\" | xargs rm"
            def publisher = config.pipelineTools.createPublishers(env.RELEASE_STAGE, env.RELEASE_SCOPE)
            publisher.unityArtifactoryUpm(env.RELEASE_STAGE == defaultReleaseType ? "artifactory_publish": "artifactory_deploy")
          }
        }

        post {
          always {
            archiveArtifacts artifacts: '**/build/logs/*.log', allowEmptyArchive: true
          }
          cleanup {
            script {
              if(config.mainPlatform.clearWs) {
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

def checkSteps(WDKConfig config, String parallelChecksPrefix, String setupStashId) {
  def conventions = new PipelineConventions(config.conventions)
  conventions.wdkParallelPrefix = parallelChecksPrefix
  conventions.wdkSetupStashId = setupStashId
  def checks = config.pipelineTools.checks.forWDKPipelines()
  def stepsForParallel = checks.wdkCoverage(config.unityVersions,
          env.RELEASE_STAGE as String, env.RELEASE_SCOPE as String,
          config.checkArgs, conventions)
  return stepsForParallel
}
