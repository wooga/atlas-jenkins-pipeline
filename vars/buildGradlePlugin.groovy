#!/usr/bin/env groovy

import net.wooga.jenkins.pipeline.config.JavaConfig

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildGradlePlugin                                                                                             //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map configMap = [:], Closure stagesConfigCls = {it -> }) {
    javaLibs(configMap) { stages ->
        stages.publish = { stage, params, JavaConfig config ->
            stage.action = {
                def publisher = config.pipelineTools.createPublishers(params.RELEASE_TYPE, params.RELEASE_SCOPE)
                publisher.gradlePlugin('gradle.publish.key', 'gradle.publish.secret')
            }
        }
    }
}
