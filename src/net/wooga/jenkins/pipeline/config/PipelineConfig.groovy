package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.PipelineTools

interface PipelineConfig {

    GradleArgs getGradleArgs()
    DockerArgs getDockerArgs()
    JenkinsMetadata getMetadata()

    PipelineTools getPipelineTools()
}