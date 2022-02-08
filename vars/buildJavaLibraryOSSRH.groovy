#!/usr/bin/env groovy

import net.wooga.jenkins.pipeline.config.JavaConfig

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildJavaLibraryOSSRH                                                                                         //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map configMap = [:]) {
    javaLibs(configMap) { stages ->
        stages.publish = { stage, params, JavaConfig config ->
            stage.action = {
                def publisher = config.pipelineTools.createPublishers(params.RELEASE_TYPE, params.RELEASE_SCOPE)
                publisher.ossrh('ossrh.publish', 'ossrh.signing.key',
                            'ossrh.signing.key_id', 'ossrh.signing.passphrase')
            }
        }
    }
}
