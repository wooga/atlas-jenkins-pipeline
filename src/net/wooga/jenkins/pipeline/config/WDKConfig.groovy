package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.BuildVersion
import net.wooga.jenkins.pipeline.PipelineTools

class WDKConfig implements PipelineConfig {

    final UnityVersionPlatform[] unityVersions
    final BaseConfig baseConfig

    static List<BuildVersion> copyBuildVersionsWithLabels(List<String> labels, List<BuildVersion> buildVersions) {
        def result = [] as List<BuildVersion>
        for (String label : labels) {
            def copyBuildVersion = copyBuildVersionsWithLabel(label, buildVersions)
            result.addAll(copyBuildVersion)
        }
        return result
    }

    static List<BuildVersion> copyBuildVersionsWithLabel(String label, List<BuildVersion> baseBuildVersions) {
        def buildVersions = []
        for (BuildVersion baseBuildVersion : baseBuildVersions) {
            if(baseBuildVersion.label != label) {
                buildVersions.add(baseBuildVersion.copy(label: label, optional: true))
            }
        }
        return buildVersions
    }

    static List<UnityVersionPlatform> collectUnityVersions(List unityVerObjs, Map configMap) {
        def index = 0
        def extraLabels = ["linux"]
        def platforms = []

        def buildVersions = BuildVersion.parseMany(unityVerObjs)
        buildVersions.addAll(copyBuildVersionsWithLabels(extraLabels, buildVersions))

        for (BuildVersion buildVersion : buildVersions) {
            def platform = Platform.forWDK(buildVersion, configMap, index == 0)
            index++
            platforms.add(new UnityVersionPlatform(platform, buildVersion))
        }
        return platforms
    }

    static WDKConfig fromConfigMap(Map configMap, Object jenkinsScript) {
        configMap.unityVersions = configMap.unityVersions ?: []

        def unityVersions = collectUnityVersions(configMap.unityVersions as List, configMap)

        if (unityVersions.isEmpty()) throw new Exception("Please provide at least one unity version.")

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


