package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.PipelineTools

class JavaConfig implements PipelineConfig {

    final BaseConfig baseConfig
    final Platform[] platforms

    static Platform[] collectPlatform(Map configMap, List<String> platformNames) {
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

    JavaConfig(BaseConfig baseConfig, Platform[] platforms) {
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

    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        JavaConfig that = (JavaConfig) o

        if (checkArgs != that.checkArgs) return false
        if (conventions != that.conventions) return false
        if (dockerArgs != that.dockerArgs) return false
        if (gradleArgs != that.gradleArgs) return false
        if (jenkins != that.jenkins) return false
        if (metadata != that.metadata) return false
        if (!Arrays.equals(platforms, that.platforms)) return false

        return true
    }

    int hashCode() {
        int result
        result = (jenkins != null ? jenkins.hashCode() : 0)
        result = 31 * result + (conventions != null ? conventions.hashCode() : 0)
        result = 31 * result + (platforms != null ? Arrays.hashCode(platforms) : 0)
        result = 31 * result + (metadata != null ? metadata.hashCode() : 0)
        result = 31 * result + (gradleArgs != null ? gradleArgs.hashCode() : 0)
        result = 31 * result + (dockerArgs != null ? dockerArgs.hashCode() : 0)
        result = 31 * result + (checkArgs != null ? checkArgs.hashCode() : 0)
        return result
    }

}
