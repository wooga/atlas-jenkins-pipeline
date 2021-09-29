package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.config.Platform

class EnclosureCreator {

    final Object jenkins
    final int buildNumber

    EnclosureCreator(Object jenkins, int buildNumber) {
        this.jenkins = jenkins
        this.buildNumber = buildNumber
    }

    Closure withNodeAndEnv(Platform platform, Closure mainCls, Closure catchCls, Closure finallyCls) {
        def testEnvironment = platform.testEnvironment +
                ["TRAVIS_JOB_NUMBER=${buildNumber}.${platform.name.toUpperCase()}"]
        return {
            def platformLabels = platform.generateTestLabelsString()
            def nodeLabels = platformLabels && !platformLabels.empty ?
                    "atlas && ${platform.generateTestLabelsString()}" : "atlas"
            jenkins.node(nodeLabels) {
                jenkins.withEnv(testEnvironment) {
                    try {
                        //TODO: split main closure into main and success
                        //TODO: success closure exceptions should "explode" (not be catch at all)
                        mainCls()
                        //successCls?.call()
                    } catch (Exception e) {
                        catchCls?.call(e)
                    } finally {
                        //TODO: split finally into finally and cleanup
                        finallyCls?.call()
                        //cleanupCls?.call()
                    }
                }
            }
        }
    }
}
