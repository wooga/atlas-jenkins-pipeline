package net.wooga.jenkins.pipeline

import net.wooga.jenkins.pipeline.assemble.Assemblers
import net.wooga.jenkins.pipeline.check.Checks
import net.wooga.jenkins.pipeline.config.PipelineConfig
import net.wooga.jenkins.pipeline.model.Docker
import net.wooga.jenkins.pipeline.model.Gradle
import net.wooga.jenkins.pipeline.publish.Publishers
import net.wooga.jenkins.pipeline.setup.Setups

class PipelineTools {

    final Gradle gradle
    final Docker docker
    final Setups setups
    final Checks checks
    final Assemblers assemblers
    private final Closure<Publishers> publishersFactory

    static PipelineTools fromConfig(Object jenkins, PipelineConfig config) {
        def gradle = Gradle.fromJenkins(jenkins, config.gradleArgs)
        def docker = Docker.fromJenkins(jenkins, config.dockerArgs)
        def setups = Setups.create(jenkins, gradle)
        def checks = Checks.create(jenkins, docker, gradle, config.metadata.buildNumber)
        def assemblers = Assemblers.fromJenkins(jenkins, gradle)
        def publishersFactory = {
            String releaseType, String releaseScope -> Publishers.fromJenkins(jenkins, gradle, releaseType, releaseScope)
        }

        return new PipelineTools(gradle, docker, setups, assemblers, checks, publishersFactory)
    }

    PipelineTools(Gradle gradle, Docker docker, Setups setups, Assemblers assemblers, Checks checks, Closure<Publishers> publishersFactory) {
        this.gradle = gradle
        this.docker = docker
        this.setups = setups
        this.assemblers = assemblers
        this.checks = checks
        this.publishersFactory = publishersFactory
    }


    Publishers createPublishers(String releaseType, String releaseScope) {
        return publishersFactory.call(releaseType, releaseScope)
    }

}
