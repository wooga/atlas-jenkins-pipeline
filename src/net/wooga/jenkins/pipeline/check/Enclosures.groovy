

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

    def withDocker(Platform platform, PackedStep mainCls, Closure catchCls = { throw it }, PackedStep finallyCls = {}) {
        enclosureCreator.withNodeAndEnv(platform,
                withCheckout(platform.checkoutDirectory, { docker.runOnImage(mainCls) }),
                catchCls,
                finallyCls
        )
    }

    def simple(Platform platform, PackedStep mainClosure, Closure catchCls = { throw it }, PackedStep finallyCls = {}) {
        enclosureCreator.withNodeAndEnv(platform,
                withCheckout(platform.checkoutDirectory, mainClosure),
                catchCls,
                finallyCls
        )
    }

    private PackedStep withCheckout(String checkoutDir, PackedStep step) {
        return {
            jenkins.dir(checkoutDir) {
                jenkins.checkout(jenkins.scm)
            }
            step()
        }
    }
}