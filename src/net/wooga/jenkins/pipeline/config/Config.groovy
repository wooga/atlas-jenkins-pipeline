package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.PipelineTools

class Config implements PipelineConfig {

    final BaseConfig baseConfig
    final Platform[] platforms

    Config(BaseConfig baseConfig, List<Platform> platforms) {
        this.baseConfig = baseConfig
        this.platforms = platforms
    }

    Platform getMainPlatform() {
        return platforms.find {it.main }
    }

    @Override
    PipelineConventions getConventions() {
        return baseConfig.conventions
    }

    @Override
    CheckArgs getCheckArgs() {
        return baseConfig.checkArgs
    }

    @Override
    GradleArgs getGradleArgs() {
        return baseConfig.gradleArgs
    }

    @Override
    DockerArgs getDockerArgs() {
        return baseConfig.dockerArgs
    }

    @Override
    JenkinsMetadata getMetadata() {
        return baseConfig.metadata
    }

    @Override
    PipelineTools getPipelineTools() {
        return PipelineTools.fromConfig(baseConfig.jenkins, this)
    }
}
