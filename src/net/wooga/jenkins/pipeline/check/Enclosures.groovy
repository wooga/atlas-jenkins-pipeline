package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.config.Config
import net.wooga.jenkins.pipeline.config.Platform

class Enclosures {

    private Config config
    private Docker docker
    private EnclosureCreator enclosureCreator

    Enclosures(Config config, Docker docker, EnclosureCreator enclosureCreator) {
        this.config = config
        this.docker = docker
        this.enclosureCreator = enclosureCreator
    }

    def withDocker(Platform platform, Closure mainCls, Closure catchCls = {throw it}, Closure finallyCls = {}) {
        enclosureCreator.withNodeAndEnv(platform, {
            docker.runOnImage(config.dockerArgs, mainCls)
        }, catchCls, finallyCls)
    }

    def simple(Platform platform, Closure mainClosure, Closure catchCls = {throw it}, Closure finallyCls = {}) {
        enclosureCreator.withNodeAndEnv(platform, mainClosure, catchCls, finallyCls)
    }
}
