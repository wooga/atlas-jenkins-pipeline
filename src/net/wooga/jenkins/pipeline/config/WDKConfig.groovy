package net.wooga.jenkins.pipeline.config


import net.wooga.jenkins.pipeline.PipelineTools

class WDKConfig implements PipelineConfig {

    final WDKUnityBuildVersion[] unityVersions
    final BaseConfig baseConfig

    static List<WDKUnityBuildVersion> collectWDKUnityBuildVersions(List unityVerObjs, Map configMap) {
        def index = 0
        def platforms = unityVerObjs.collect { unityVerObj ->
            WDKUnityBuildVersion.Parse(unityVerObj, configMap, index++ == 0)
        }
        return platforms
    }

    static WDKConfig fromConfigMap(Map configMap, Object jenkinsScript) {
        configMap.unityVersions = configMap.unityVersions ?: []

        def unityVersions = collectWDKUnityBuildVersions(configMap.unityVersions as List, configMap)

        if (unityVersions.isEmpty()) throw new Exception("Please provide at least one unity version.")

        def baseConfig = BaseConfig.fromConfigMap(configMap, jenkinsScript)

        return new WDKConfig(unityVersions, baseConfig)
    }

    WDKConfig(List<WDKUnityBuildVersion> unityVersions, BaseConfig baseConfig) {
        this.unityVersions = unityVersions
        this.baseConfig = baseConfig
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
        return null
    }

    @Override
    JenkinsMetadata getMetadata() {
        return baseConfig.metadata
    }

    @Override
    PipelineTools getPipelineTools() {
        return PipelineTools.fromConfig(baseConfig.jenkins, this)
    }

    Platform getMainPlatform() {
        return unityVersions.find {it.platform.main }.platform
    }
}


