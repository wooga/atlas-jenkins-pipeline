#!/usr/bin/env groovy
import net.wooga.jenkins.pipeline.config.Config

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildJavaLibrary                                                                                              //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map configMap = [:]) {
    javaLibs(configMap) { stages ->
        stages.publish = { stage, params, Config config ->
            stage.action = {
                def publisher = config.pipelineTools.createPublishers(params.RELEASE_TYPE, params.RELEASE_SCOPE)
                publisher.bintray('bintray.publish')
            }
        }
    }
}
