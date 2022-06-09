package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.check.steps.PackedStep
import net.wooga.jenkins.pipeline.config.Platform
import net.wooga.jenkins.pipeline.model.Docker

class Enclosures {

    private Object jenkins
    private Docker docker
    private EnclosureCreator enclosureCreator

    Enclosures(Object jenkins, Docker docker, EnclosureCreator enclosureCreator) {
        this.jenkins = jenkins
        this.docker = docker
        this.enclosureCreator = enclosureCreator
    }

    def withDocker(Platform platform, PackedStep mainCls, Closure catchCls = {throw it}, PackedStep finallyCls = {}) {
        return enclosureCreator.nodeForPlatform(platform,
                withCheckout(platform.checkoutDirectory, { docker.runOnImage(mainCls) }),
                withOptional(platform.name, platform.optional, catchCls),
                withCleanup(platform.clearWs, finallyCls)
        )
    }

    def simple(Platform platform, PackedStep mainClosure, Closure catchCls = {throw it}, PackedStep finallyCls = {}) {
        return enclosureCreator.nodeForPlatform(platform,
                withCheckout(platform.checkoutDirectory, mainClosure),
                withOptional(platform.name, platform.optional, catchCls),
                withCleanup(platform.clearWs, finallyCls)
        )
    }

    private PackedStep withCheckout(String checkoutDir, PackedStep step) {
        return {
            jenkins.dir(checkoutDir) {
                jenkins.checkout(jenkins.scm)
                step()
            }
        }
    }

    private PackedStep withCleanup(boolean hasCleanup, PackedStep step) {
        return {
            step()
            if(hasCleanup) {
                jenkins.cleanWs()
            }
        }
    }


    //TODO tests
    private Closure withOptional(String name, boolean isOptional, Closure catchCls) {
        return  { exception ->
            if (isOptional) {
                jenkins.unstable(message: "Optional run ${name} failed, continuing other runs\n${exception.toString()}")
            } else {
                catchCls(exception)
            }
        }
    }
}
