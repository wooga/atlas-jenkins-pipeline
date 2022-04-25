package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.PipelineTools

class JSConfig implements PipelineConfig {

    BaseConfig baseConfig
    Platform[] platforms

    static Platform[] collectPlatforms(Map configMap, List<String> platformNames) {
        def index = 0
        return platformNames.collect { String platformName ->
            def platform = Platform.forJS(platformName, configMap, index == 0)
            index++
            return platform
        }
    }

    static JSConfig fromConfigMap(Map configMap, Object jenkinsScript) {
        configMap.platforms = configMap.platforms ?: ['macos']
        def baseConfig = BaseConfig.fromConfigMap(configMap, jenkinsScript)
        def platforms = collectPlatforms(configMap, configMap.platforms as List<String>)

        return new JSConfig(baseConfig, platforms as Platform[])
    }

    JSConfig(BaseConfig baseConfig, Platform[] platforms) {
        this.baseConfig = baseConfig
        this.platforms = platforms
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
