package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.BuildVersion
import net.wooga.jenkins.pipeline.PipelineTools

class WDKConfig implements PipelineConfig {

    final UnityVersionPlatform[] unityVersions
    final BaseConfig baseConfig

    static List<UnityVersionPlatform> collectUnityVersions(List unityVerObjs, Map configMap) {
        def index = 0
        return unityVerObjs.collect { Object unityVersionObj ->
            def buildVersion = BuildVersion.parse(unityVersionObj)
            def platform = Platform.forWDK(buildVersion, configMap, index == 0)
            index++
            return new UnityVersionPlatform(platform, buildVersion)
        }
    }

    static WDKConfig fromConfigMap(Map configMap, Object jenkinsScript) {
        configMap.unityVersions = configMap.unityVersions ?: []
        def unityVersions = collectUnityVersions(configMap.unityVersions as List, configMap)
        if (unityVersions.isEmpty()) throw new IllegalArgumentException("Please provide at least one unity version.")

        def baseConfig = BaseConfig.fromConfigMap(configMap, jenkinsScript)

        return new WDKConfig(unityVersions, baseConfig)
    }

    WDKConfig(List<UnityVersionPlatform> unityVersions, BaseConfig baseConfig) {
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


