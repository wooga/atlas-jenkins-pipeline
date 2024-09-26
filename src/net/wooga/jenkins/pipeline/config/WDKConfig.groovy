package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.PipelineTools

class WDKConfig implements PipelineConfig {

    final WdkUnityBuildVersion[] unityVersions
    final BaseConfig baseConfig

    static List<WdkUnityBuildVersion> copyBuildVersionsWithLabels(Map configMap,
                                                                  List<String> labels,
                                                                  List<WdkUnityBuildVersion> baseBuildVersions) {
        return labels.collectMany { label ->
            baseBuildVersions.findAll{ wdkUnityBuildVersion -> wdkUnityBuildVersion.unityBuildVersion.label != label}
                    .collect{baseWdkUnityBuildVersion -> baseWdkUnityBuildVersion.copy(configMap, label)}
        }
    }

    static List<WdkUnityBuildVersion> collectWdkUnityBuildVersions(Map configMap, List<String> extraLabels = []) {
        def index = 0
        def unityVerObjs = configMap.unityVersions as List
        def wdkUnityBuildVersions = unityVerObjs.collect { unityVerObj ->
            WdkUnityBuildVersion.Parse(unityVerObj, configMap, index++ == 0)
        }
        def extraBuildVersions = copyBuildVersionsWithLabels(configMap, extraLabels, wdkUnityBuildVersions)
        wdkUnityBuildVersions.addAll(extraBuildVersions)
        return wdkUnityBuildVersions
    }

    static WDKConfig fromConfigMap(Map configMap, Object jenkinsScript, List<String> extraLabels = []) {
        configMap.unityVersions = configMap.unityVersions ?: []

        def unityVersions = collectWdkUnityBuildVersions(configMap, extraLabels)

        if (unityVersions.isEmpty()) throw new Exception("Please provide at least one unity version.")

        def baseConfig = BaseConfig.fromConfigMap(configMap, jenkinsScript)

        return new WDKConfig(unityVersions, baseConfig)
    }

    WDKConfig(List<WdkUnityBuildVersion> unityVersions, BaseConfig baseConfig) {
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


