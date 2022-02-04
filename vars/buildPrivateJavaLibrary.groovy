#!/usr/bin/env groovy

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//                                                                                                                    //
// Step buildJavaLibrary                                                                                              //
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
              artifactoryOSSRH('artifactory_publish',
                      'ossrh.signing.key',
                      'ossrh.signing.key_id',
                      'ossrh.signing.passphrase')
            }
          })
}
