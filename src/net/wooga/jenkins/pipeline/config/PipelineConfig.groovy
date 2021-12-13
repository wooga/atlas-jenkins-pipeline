package net.wooga.jenkins.pipeline.config

interface PipelineConfig {

    GradleArgs getGradleArgs()
    DockerArgs getDockerArgs()
    JenkinsMetadata getMetadata()

}