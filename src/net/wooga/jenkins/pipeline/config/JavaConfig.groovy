package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.PipelineTools

class JavaConfig implements PipelineConfig {

    final BaseConfig baseConfig
    final Platform[] platforms

    static List<Platform> collectPlatform(Map configMap, List<String> platformNames) {
        def index = 0
        return platformNames.collect { String platformName ->
            def platform = Platform.forJava(platformName, configMap, index == 0)
            index++
            return platform
        }
    }

    static JavaConfig fromConfigMap(Map config, Object jenkinsScript) {
        config.platforms = config.platforms ?: ['macos','windows']
        def platforms = collectPlatform(config, config.platforms as List<String>)
        def baseConfig = BaseConfig.fromConfigMap(config, jenkinsScript)

        return new JavaConfig(baseConfig, platforms)
    }

    JavaConfig(BaseConfig baseConfig, List<Platform> platforms) {
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
