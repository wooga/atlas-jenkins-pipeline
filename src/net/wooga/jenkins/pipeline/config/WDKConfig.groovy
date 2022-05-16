package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.BuildVersion
import net.wooga.jenkins.pipeline.PipelineTools

class WDKConfig implements PipelineConfig {

    final UnityVersionPlatform[] unityVersions
    final BaseConfig baseConfig
    final String buildLabel

    static List<UnityVersionPlatform> collectUnityVersions(List unityVerObjs, String buildLabel, Map configMap) {
        def index = 0
        return unityVerObjs.collect { Object unityVersionObj ->
            def buildVersion = BuildVersion.parse(unityVersionObj)
            def platform = Platform.forWDK(buildVersion, buildLabel, configMap, index == 0)
            index++
            return new UnityVersionPlatform(platform, buildVersion)
        }
    }

    static WDKConfig fromConfigMap(String buildLabel, Map configMap, Object jenkinsScript) {
        configMap.unityVersions = configMap.unityVersions ?: []
        def unityVersions = collectUnityVersions(configMap.unityVersions as List, buildLabel, configMap)
        if (unityVersions.isEmpty()) throw new IllegalArgumentException("Please provide at least one unity version.")

        def baseConfig = BaseConfig.fromConfigMap(configMap, jenkinsScript)

        return new WDKConfig(unityVersions, baseConfig, buildLabel)
    }

    WDKConfig(List<UnityVersionPlatform> unityVersions, BaseConfig baseConfig, String buildLabel) {
        this.unityVersions = unityVersions
        this.baseConfig = baseConfig
        this.buildLabel = buildLabel
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
}


