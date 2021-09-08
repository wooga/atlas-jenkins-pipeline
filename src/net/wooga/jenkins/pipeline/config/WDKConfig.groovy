package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.BuildVersion

class WDKConfig {

    static WDKConfig fromConfigMap(String buildLabel, Map configMap) {
        configMap.unityVersions = configMap.unityVersions?: []
        def unityVersions = configMap.unityVersions.collect { unityVersionObj ->
            def buildVersion = BuildVersion.parse(unityVersionObj)
            def platform = Platform.forWDK(buildVersion, buildLabel, configMap)
            return new UnityVersionPlatform(platform, buildVersion)
        }
        if(unityVersions.empty) throw new IllegalArgumentException("Please provide at least one unity version.")

        boolean refreshDependencies = configMap.refreshDependencies ?: false
        String logLevel = configMap.logLevel ?: ''

        return new WDKConfig(unityVersions, refreshDependencies, logLevel, buildLabel)
    }

    final UnityVersionPlatform[] unityVersions
    final boolean refreshDependencies
    final String logLevel
    final String buildLabel

    WDKConfig(List<UnityVersionPlatform> unityVersions, boolean refreshDependencies, String logLevel, String buildLabel) {
        this.unityVersions = unityVersions
        this.refreshDependencies = refreshDependencies
        this.logLevel = logLevel
        this.buildLabel = buildLabel
    }
}


