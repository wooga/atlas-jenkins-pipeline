#!/usr/bin/env groovy

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildGradlePlugin                                                                                             //
//                                                                                                                    //
//                                                                                                                    //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

def call(Map configMap = [:]) {
  javaLibs(configMap,
          { params, config ->
            javaLibCheck config: config
          },
          { params, config ->
            publish(params.RELEASE_TYPE, params.RELEASE_SCOPE) {
              gradlePlugin('gradle.publish.key', 'gradle.publish.secret')
            }
          })
}
