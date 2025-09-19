#!/usr/bin/env groovy
import net.wooga.jenkins.pipeline.check.steps.BasicSteps
import net.wooga.jenkins.pipeline.stages.Stages
import net.wooga.jenkins.pipeline.config.JavaConfig

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildGradlePlugin                                                                                             //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map configMap = [:], Closure stepsConfigCls) {
  def defaultReleaseType = "snapshot"
  def defaultReleaseScope = ""
  //organize configs inside neat object. Defaults are defined there as well
  configMap.logLevel = configMap.get("logLevel", params.LOG_LEVEL?: env.LOG_LEVEL as String)
  configMap.showStackTrace = configMap.get("showStackTrace", params.STACK_TRACE as Boolean)
  configMap.refreshDependencies = configMap.get("refreshDependencies", params.REFRESH_DEPENDENCIES as Boolean)
  configMap.clearWs = configMap.get("clearWs", params.CLEAR_WS as boolean)
  def config = JavaConfig.fromConfigMap(configMap, this)
  def actions = Stages.fromClosure(params as Map, config, stepsConfigCls)
  def mainPlatform = config.mainPlatform.name

  pipeline {
    agent none

    options {
      buildDiscarder(logRotator(artifactNumToKeepStr:'40'))
    }

    parameters {
      choice(choices: [defaultReleaseType, "rc", "final"], description: 'Choose the distribution type', name: 'RELEASE_TYPE')
      choice(choices: [defaultReleaseScope, "patch", "minor", "major"], description: 'Choose the change scope', name: 'RELEASE_SCOPE')
      choice(choices: ["", "quiet", "info", "warn", "debug"], description: 'Choose the log level', name: 'LOG_LEVEL')
      booleanParam(defaultValue: false, description: 'Whether to log truncated stacktraces', name: 'STACK_TRACE')
      booleanParam(defaultValue: false, description: 'Whether to refresh dependencies', name: 'REFRESH_DEPENDENCIES')
      booleanParam(defaultValue: false, description: 'Whether to clear workspaces after build', name: 'CLEAR_WS')
    }

    environment {
      ATLAS_READ = credentials('artifactory_read') //needed to read private packages
    }

    stages {
      stage('Preparation') {
        agent any
        steps {
          script {
            env.RELEASE_TYPE = params.RELEASE_TYPE?: defaultReleaseType
            env.RELEASE_SCOPE = params.RELEASE_SCOPE?: defaultReleaseScope
          }
          sendSlackNotification "STARTED", true
        }

        post {
          cleanup {
            script {
              if(config.mainPlatform.clearWs) {
                cleanWs()
              }
            }
          }
        }
      }

      stage("check") {
        agent none
        when {
          beforeAgent true
          expression { return actions.check.runWhenOrElse { env.RELEASE_TYPE == "snapshot" } }
        }

        steps {
          script {
            actions.check.runActionOrElse {
              withEnv(["COVERALLS_PARALLEL=true"]) {
                def javaChecks = config.pipelineTools.checks.forJavaPipelines()
                def checksForParallel = javaChecks.gradleCheckWithCoverage(config.platforms, config.checkArgs, config.conventions)
                parallel checksForParallel
              }
            }
          }
        }
        post {
          success {
            script {
              if(config.checkArgs.coveralls.token) {
                httpRequest httpMode: 'POST', ignoreSslErrors: true, validResponseCodes: '100:599', url: "https://coveralls.io/webhook?repo_token=${config.checkArgs.coveralls.token}"
              }
            }
          }
       }
     }

      stage('publish') {
        when {
          beforeAgent true
          //TODO this should be variable, as private libs publishes on snapshot
          expression { return actions.publish.runWhenOrElse { env.RELEASE_TYPE != "snapshot" }  }
        }
        agent {
          label "$mainPlatform && atlas"
        }
        environment {
          GRGIT                 = credentials('github_access')
          GRGIT_USER            = "${GRGIT_USR}"
          GRGIT_PASS            = "${GRGIT_PSW}"
          GITHUB_LOGIN          = "${GRGIT_USR}"
          GITHUB_PASSWORD       = "${GRGIT_PSW}"
        }


        steps {
          script {
            def withJavaVersion = new BasicSteps(this).javaVersionWrapper(config.conventions.javaVersion, config.conventions.javaVersionFile)
            withJavaVersion {
              actions.publish.runActionOrElse {
                  error "This pipeline has no publish action whatsoever, " +
                          "if you don't want to ever run publish, set 'when' to always return false"
              }
            }
          }
        }

        post {
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
