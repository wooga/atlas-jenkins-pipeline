#!/usr/bin/env groovy

import net.wooga.jenkins.pipeline.stages.Stages
import net.wooga.jenkins.pipeline.config.JavaConfig

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildGradlePlugin                                                                                             //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map configMap = [:], Closure stepsConfigCls) {
    configMap.logLevel = configMap.get("logLevel", params.LOG_LEVEL ?: env.LOG_LEVEL as String)
    configMap.showStackTrace = configMap.get("showStackTrace", params.STACK_TRACE as Boolean)
    configMap.refreshDependencies = configMap.get("refreshDependencies", params.REFRESH_DEPENDENCIES as Boolean)
    configMap.clearWs = configMap.get("clearWs", params.CLEAR_WS as boolean)
    def config = JavaConfig.fromConfigMap(configMap, this)
    def actions = Stages.fromClosure(params as Map, config, stepsConfigCls)
    def mainPlatform = config.mainPlatform.name
    def nodes = config.pipelineTools.checks.nodeCreator

    def stages = [
        lazyStage(actions.check.name ?: "check") {
            nodes.node([
                when : { actions.check.runWhenOrElse { params.RELEASE_TYPE == "snapshot" } },
                steps: {
                    actions.check.runActionOrElse {
                        withEnv(["COVERALLS_PARALLEL=true"]) {
                            def javaChecks = config.pipelineTools.checks.forJavaPipelines()
                            def checksForParallel = javaChecks.gradleCheckWithCoverage(config.platforms, config.checkArgs, config.conventions)
                            parallel checksForParallel
                        }
                    }
                },
                after: { exception ->
                    if (!exception && config.checkArgs.coveralls.token) {
                        httpRequest httpMode: 'POST', ignoreSslErrors: true, validResponseCodes: '100:599', url: "https://coveralls.io/webhook?repo_token=${config.checkArgs.coveralls.token}"
                    }
                }
            ])
        },
        lazyStage(actions.check.name ?: "publish") {
            nodes.node([
                label      : "$mainPlatform && atlas",
                credentials: [
                        usernamePassword(credentialsId: 'github_access',
                                usernameVariable: 'GRGIT_USER', passwordVariable: 'GRGIT_PASS'),
                        usernamePassword(credentialsId: 'github_access',
                                usernameVariable: 'GITHUB_LOGIN', passwordVariable: 'GITHUB_PASSWORD')
                ],
                when       : { actions.publish.runWhenOrElse { params.RELEASE_TYPE != "snapshot" } },
                steps      : {
                    actions.publish.runActionOrElse {
                        error "This pipeline has no publish action whatsoever, " +
                                "if you don't want to ever run publish, set 'when' to always return false"
                    }
                },
                after      : { exception ->
                    if (config.mainPlatform.clearWs) {
                        cleanWs()
                    }
                }
            ])
        }
    ]
    declarativePipelineTemplate(config, stages)
}

def lazyStage(String name, Closure content) {
    return {
        stage(name) {
            content(name)
        }
    }
}