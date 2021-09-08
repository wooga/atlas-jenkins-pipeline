package net.wooga.jenkins.pipeline.check


import net.wooga.jenkins.pipeline.config.DockerArgs
import net.wooga.jenkins.pipeline.config.Platform
import net.wooga.jenkins.pipeline.model.Docker

class Enclosures {

    private Docker docker
    private DockerArgs dockerArgs
    private EnclosureCreator enclosureCreator

    Enclosures(Docker docker, DockerArgs dockerArgs, EnclosureCreator enclosureCreator) {
        this.docker = docker
        this.dockerArgs = dockerArgs
        this.enclosureCreator = enclosureCreator
    }

    def withDocker(Platform platform, Closure mainCls, Closure catchCls = {throw it}, Closure finallyCls = {}) {
        enclosureCreator.withNodeAndEnv(platform, {
            docker.runOnImage(dockerArgs, mainCls)
        }, catchCls, finallyCls)
    }

    def simple(Platform platform, Closure mainClosure, Closure catchCls = {throw it}, Closure finallyCls = {}) {
        enclosureCreator.withNodeAndEnv(platform, mainClosure, catchCls, finallyCls)
    }
}
