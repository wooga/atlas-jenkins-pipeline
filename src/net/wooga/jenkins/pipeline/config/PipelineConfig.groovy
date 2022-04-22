package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.PipelineTools

interface PipelineConfig {

    PipelineConventions getConventions()
    CheckArgs getCheckArgs()
    GradleArgs getGradleArgs()
    DockerArgs getDockerArgs()
    JenkinsMetadata getMetadata()

    PipelineTools getPipelineTools()
}