#!/usr/bin/env groovy
import net.wooga.jenkins.pipeline.config.JavaConfig

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildJavaLibrary                                                                                              //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map configMap = [:]) {
    javaLibs(configMap) { stages ->
        stages.publish = { stage, params, JavaConfig config ->
            stage.action = {
                def publisher = config.pipelineTools.createPublishers(env.RELEASE_TYPE, env.RELEASE_SCOPE)
                publisher.bintray('bintray.publish')
            }
        }
    }
}
