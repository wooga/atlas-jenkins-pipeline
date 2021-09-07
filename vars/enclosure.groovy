

import net.wooga.jenkins.pipeline.config.Config
import net.wooga.jenkins.pipeline.config.Platform

def call(Platform platform, Config config) {
    return [
        withDocker: { Closure mainCls, Closure catchCls = {throw it}, Closure finallyCls = {} ->
            return nodeEnclosure(platform, config.metadata.buildNumber, {
                dockerWrapper(config.dockerArgs).wrap(mainCls)
            }, catchCls, finallyCls)
        },
        simple: { Closure mainClosure, Closure catchCls = {throw it}, Closure finallyCls = {} ->
            nodeEnclosure(platform, config.metadata.buildNumber, mainClosure, catchCls, finallyCls)
        }
    ]
}

Closure nodeEnclosure(Platform platform, int buildNumber,
             Closure mainCls, Closure catchCls, Closure finallyCls) {
    def testEnvironment = platform.testEnvironment +
            ["TRAVIS_JOB_NUMBER=${buildNumber}.${platform.name.toUpperCase()}"]
    return {
        def platformLabels = platform.generateTestLabelsString()
        def nodeLabels = platformLabels && !platformLabels.empty?
                "atlas && ${platform.generateTestLabelsString()}" : "atlas"
        node(nodeLabels) {
            withEnv(testEnvironment) {
                try {
                    mainCls()
                } catch(Exception e) {
                    catchCls?.call(e)
                }finally {
                    finallyCls?.call()
                }
            }
        }
    }
}
