package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.check.steps.PackedStep
import net.wooga.jenkins.pipeline.config.Platform
import net.wooga.jenkins.pipeline.model.Docker

class Enclosures {

    private Object jenkins
    private Docker docker
    private NodeCreator enclosureCreator
    private int buildNumber

    Enclosures(Object jenkins, Docker docker, NodeCreator enclosureCreator, int buildNumber) {
        this.jenkins = jenkins
        this.docker = docker
        this.enclosureCreator = enclosureCreator
        this.buildNumber = buildNumber
    }

    Closure withDocker(Platform platform, PackedStep mainCls, Closure catchCls = {throw it}, Closure finallyCls = {ex ->}) {
        return forPlatform(platform,
                withCheckout(platform.checkoutDirectory, { docker.runOnImage(mainCls) }),
                catchCls,
                withCleanup(platform.clearWs, finallyCls)
        )
    }

    Closure simple(Platform platform, PackedStep mainClosure, Closure catchCls = {throw it}, Closure finallyCls = {ex ->}) {
        return forPlatform(platform,
                withCheckout(platform.checkoutDirectory, mainClosure),
                catchCls,
                withCleanup(platform.clearWs, finallyCls)
        )
    }

    private Closure forPlatform(Platform platform, PackedStep mainClosure,
                                Closure catchCls = {throw it}, Closure finallyCls = {ex -> }) {
        def testEnvironment = platform.testEnvironment +
                ["TRAVIS_JOB_NUMBER=${buildNumber}.${platform.name.toUpperCase()}"] as List<String>
        def platformLabels = platform.generateTestLabelsString()
        def nodeLabels = platformLabels && !platformLabels.empty ?
                "atlas && ${platform.generateTestLabelsString()}" : "atlas"
        return enclosureCreator.nodeWithEnv(nodeLabels, testEnvironment, mainClosure.&call, catchCls, finallyCls)
    }

    private PackedStep withCheckout(String checkoutDir, PackedStep step) {
        return {
            jenkins.dir(checkoutDir) {
                jenkins.checkout(jenkins.scm)
            }
            step()
        }
    }

    private Closure withCleanup(boolean hasCleanup, Closure step) {
        return { exception ->
            step(exception)
            if(hasCleanup) {
                jenkins.cleanWs()
            }
        }
    }
}
