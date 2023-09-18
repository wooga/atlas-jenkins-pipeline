#!/usr/bin/env groovy

import net.wooga.jenkins.pipeline.config.JavaConfig

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildGradlePlugin                                                                                             //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map configMap = [:]) {
    javaLibs(configMap) { stages ->
        stages.publish = { stage, params, JavaConfig config ->
            stage.action = {
                def publisher = config.pipelineTools.createPublishers(env.RELEASE_TYPE, env.RELEASE_SCOPE)
                publisher.gradlePlugin('gradle.publish.key', 'gradle.publish.secret', config.conventions)
            }
        }
    }
}
