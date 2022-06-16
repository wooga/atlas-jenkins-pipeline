#!/usr/bin/env groovy

import net.wooga.jenkins.pipeline.config.JavaConfig

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildGradlePlugin                                                                                             //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map configMap = [:], Closure stagesConfigCls = {it -> }) {
    def checkDir = configMap.checkDir?: "."
    javaLibs(configMap) { stages ->
        stages.check = { check, params, config ->
            check.when = {
                anyOf {
                    changeset comparator: 'GLOB', pattern: "${checkDir}/**"
                    isRestartedRun()
                    triggeredBy 'UserIdCause'
                }
            }
        }
        stages.publish = { publish, params, JavaConfig config ->
            publish.when = { false }
        }
        stagesConfigCls(stages)
    }
}
