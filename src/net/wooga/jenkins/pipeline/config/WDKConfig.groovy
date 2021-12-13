package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.BuildVersion

class WDKConfig implements PipelineConfig {

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
        def gradleArgs = GradleArgs.fromConfigMap(configMap)
        def jenkinsMetadata = JenkinsMetadata.fromScript(jenkinsScript)

        return new WDKConfig(unityVersions, sonarArgs, gradleArgs, jenkinsMetadata, buildLabel)
    }

    final UnityVersionPlatform[] unityVersions
    final SonarQubeArgs sonarArgs
    final GradleArgs gradleArgs
    final JenkinsMetadata metadata
    final String buildLabel

    WDKConfig(List<UnityVersionPlatform> unityVersions, SonarQubeArgs sonarArgs, GradleArgs gradleArgs,
              JenkinsMetadata metadata, String buildLabel) {
        this.unityVersions = unityVersions
        this.sonarArgs = sonarArgs
        this.gradleArgs = gradleArgs
        this.metadata = metadata
        this.buildLabel = buildLabel
    }

    @Override
    DockerArgs getDockerArgs() {
        return null
    }
}


