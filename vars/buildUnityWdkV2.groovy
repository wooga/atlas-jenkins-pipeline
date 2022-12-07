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

  def config = WDKConfig.fromConfigMap("macos", configMap, this)
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
//      UPM_USER_CONFIG_FILE = credentials('atlas-upm-credentials') cant be here
      LOG_LEVEL = "${config.gradleArgs.logLevel}"
      ATLAS_READ = credentials('artifactory_read') //needed for gradle sto read private packages
    }

    parameters {
      // TODO: Rename RELEASE_TYPE to RELEASE_STAGE
      choice(name: 'RELEASE_STAGE', choices: ["snapshot", "preflight", "rc", "final"], description: 'Choose the distribution type')
      choice(name: 'RELEASE_SCOPE', choices: ["", "patch", "minor", "major"], description: 'Choose the change scope')
      choice(name: 'LOG_LEVEL', choices: ["", "quiet", "info", "warn", "debug"], description: 'Choose the log level')
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
                archiveArtifacts artifacts: '**/build/logs/*.log', allowEmptyArchive: true
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
                stash(name: 'upm_setup_w', useDefaultExcludes: true, includes: "**/Packages/package.lock, " +
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


      stage("Check") {
        failFast true
        parallel {
          stage("check paket") {
            environment {
              UNITY_PACKAGE_MANAGER = 'paket'
            }
            steps {
              script {
                def conventions = new PipelineConventions(config.conventions);
                conventions.wdkParallelPrefix = "paket check "
                conventions.wdkSetupStashId = "paket_setup_w"
                def checks = config.pipelineTools.checks.forWDKPipelines()
                def stepsForParallel = checks.wdkCoverage(config.unityVersions,
                        params.RELEASE_STAGE as String, params.RELEASE_SCOPE as String,
                        config.checkArgs, conventions)
                parallel stepsForParallel
              }
            }
          }
          stage("check upm") {
            environment {
//              UPM_USER_CONFIG_FILE = credentials('atlas-upm-credentials')
              UNITY_PACKAGE_MANAGER = 'upm'
            }
            steps {
              script {
                def conventions = new PipelineConventions(config.conventions);
                conventions.wdkParallelPrefix = "upm check "
                conventions.wdkSetupStashId = "upm_setup_w"
                def checks = config.pipelineTools.checks.forWDKPipelines()
                def stepsForParallel = checks.wdkCoverage(config.unityVersions,
                        params.RELEASE_STAGE as String, params.RELEASE_SCOPE as String,
                        config.checkArgs, conventions)
                parallel stepsForParallel
              }
            }
          }
        }
      }


      // Verify that package resolution work using paket
//            stage('verify paket') {
//                when {
//                    beforeAgent true
//                    expression {
//                        return params.RELEASE_STAGE == "snapshot"
//                    }
//                }
//            }
//
//            // Switch over to paket packages
//            stage('setup') {
//                agent {
//                    label "atlas && $config.buildLabel"
//                }
//
//                environment {
//                    UNITY_PACKAGE_MANAGER = 'paket'
//                }
//
//                steps {
//                    script {
//                        def setup = config.pipelineTools.setups
//                        setup.wdk(params.RELEASE_STAGE as String, params.RELEASE_SCOPE as String)
//                    }
//                }
//
//                post {
//                    success {
//                        stash(name: 'setup_w', useDefaultExcludes: true, includes: "paket.lock, .gradle/**, **/build/**, .paket/**, packages/**, paket-files/**, **/Paket.Unity3D/**, **/Wooga/Plugins/**")
//                    }
//
//                    always {
//                        archiveArtifacts artifacts: '**/build/logs/*.log', allowEmptyArchive: true
//                    }
//
//                    cleanup {
//                        cleanWs()
//                    }
//                }
//            }
//
//            // Now run the tests while using paket packages
//            stage("check") {
//                steps {
//                    script {
//                        def checks = config.pipelineTools.checks.forWDKPipelines()
//                        def stepsForParallel = checks.wdkCoverage(config.unityVersions,
//                                params.RELEASE_STAGE as String, params.RELEASE_SCOPE as String,
//                                config.checkArgs, config.conventions)
//                        parallel stepsForParallel
//                    }
//                }
//            }
//
//        }
//
//        // Now do package resolution through upm
//        stage('setup') {
//
//            agent {
//                label "atlas && $config.buildLabel"
//            }
//
//            environment {
//                UNITY_PACKAGE_MANAGER = 'paket'
//            }
//
//            steps {
//                sendSlackNotification "STARTED", true
//                script {
//                    def setup = config.pipelineTools.setups
//                    setup.wdk(params.RELEASE_STAGE as String, params.RELEASE_SCOPE as String)
//                }
//            }
//
//            post {
//                success {
//                    stash(name: 'setup_w', useDefaultExcludes: true, includes: "paket.lock, .gradle/**, **/build/**, .paket/**, packages/**, paket-files/**, **/Paket.Unity3D/**, **/Wooga/Plugins/**")
//                }
//
//                always {
//                    archiveArtifacts artifacts: '**/build/logs/*.log', allowEmptyArchive: true
//                }
//
//                cleanup {
//                    cleanWs()
//                }
//            }
//        }

      stage('build') {
        failFast true
        parallel {
          stage('assemble package') {
            agent {
              label "atlas && $config.buildLabel"
            }
          environment {
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
                archiveArtifacts artifacts: 'build/outputs/*.unitypackage', allowEmptyArchive: true
                archiveArtifacts artifacts: '**/build/logs/*.log', allowEmptyArchive: true
              }
              cleanup {
                cleanWs()
              }
            }
          }
//                stage("check") {
//                    when {
//                        beforeAgent true
//                        expression {
//                            return params.RELEASE_STAGE == "snapshot"
//                        }
//                    }
//
//                    steps {
//                        script {
//                            def checks = config.pipelineTools.checks.forWDKPipelines()
//                            def stepsForParallel = checks.wdkCoverage(config.unityVersions,
//                                    params.RELEASE_STAGE as String, params.RELEASE_SCOPE as String,
//                                    config.checkArgs, config.conventions)
//                            parallel stepsForParallel
//                        }
//                    }
//                }
        }
      }

      stage('publish') {
        agent {
          label "atlas && $config.buildLabel"
        }

        environment {

          GRGIT = credentials('github_access')
          GRGIT_USER = "${GRGIT_USR}"
          GRGIT_PASS = "${GRGIT_PSW}"
          GITHUB_LOGIN = "${GRGIT_USR}"
          GITHUB_PASSWORD = "${GRGIT_PSW}"
        }

        steps {
          unstash 'upm_setup_w'
          unstash 'wdk_output'
          script {
            def applicationsHome = env.APPLICATIONS_HOME ?: ""
            def unityExecPackagePath = env.UNITY_EXEC_PACKAGE_PATH ?: ""
            def unityPath = "${applicationsHome}/${config.unityVersions[0].stepDescription}/${unityExecPackagePath}"

            def publisher = config.pipelineTools.createPublishers(params.RELEASE_STAGE, params.RELEASE_SCOPE)
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
