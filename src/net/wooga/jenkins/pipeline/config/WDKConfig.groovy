package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.BuildVersion

class WDKConfig {

    static WDKConfig fromConfigMap(String buildLabel, Map configMap, Object jenkinsScript) {
        configMap.unityVersions = configMap.unityVersions ?: []
        def unityVerObjs = configMap.unityVersions as List
        def index = 0
        def unityVersions = unityVerObjs.collect { Object unityVersionObj ->
            def buildVersion = BuildVersion.parse(unityVersionObj)
            def platform = Platform.forWDK(buildVersion, buildLabel, configMap, index == 0)
            index++
            return new UnityVersionPlatform(platform, buildVersion)
        }
        if (unityVersions.isEmpty()) throw new IllegalArgumentException("Please provide at least one unity version.")

        def sonarArgs = SonarQubeArgs.fromConfigMap(configMap)
        def jenkinsMetadata = JenkinsMetadata.fromScript(jenkinsScript)
        boolean refreshDependencies = configMap.refreshDependencies ?: false
        String logLevel = configMap.logLevel ?: ''

        return new WDKConfig(unityVersions, sonarArgs, jenkinsMetadata, refreshDependencies, logLevel, buildLabel)
    }

    final UnityVersionPlatform[] unityVersions
    final SonarQubeArgs sonarArgs
    final JenkinsMetadata metadata
    final boolean refreshDependencies
    final String logLevel
    final String buildLabel

    WDKConfig(List<UnityVersionPlatform> unityVersions, SonarQubeArgs sonarArgs, JenkinsMetadata metadata,
              boolean refreshDependencies, String logLevel, String buildLabel) {
        this.unityVersions = unityVersions
        this.sonarArgs = sonarArgs
        this.metadata = metadata
        this.refreshDependencies = refreshDependencies
        this.logLevel = logLevel
        this.buildLabel = buildLabel
    }
}


