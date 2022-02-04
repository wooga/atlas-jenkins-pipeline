package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.config.Platform

import java.util.function.Consumer

class EnclosureCreator {

    final Object jenkins
    final int buildNumber

    EnclosureCreator(Object jenkins, int buildNumber) {
        this.jenkins = jenkins
        this.buildNumber = buildNumber
    }

    Closure withNodeAndEnv(Platform platform, Runnable mainCls, Closure catchCls, Runnable finallyCls) {
        def testEnvironment = platform.testEnvironment +
                ["TRAVIS_JOB_NUMBER=${buildNumber}.${platform.name.toUpperCase()}"]
        return {
            def platformLabels = platform.generateTestLabelsString()
            def nodeLabels = platformLabels && !platformLabels.empty ?
                    "atlas && ${platform.generateTestLabelsString()}" : "atlas"
            jenkins.node(nodeLabels) {
                jenkins.withEnv(testEnvironment) {
                    try {
                        mainCls.run()
                    } catch (Exception e) {
                        catchCls?.call(e)
                    } finally {
                        finallyCls?.run()
                    }
                }
            }
        }
    }
}
