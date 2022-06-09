package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.BuildVersion
import net.wooga.jenkins.pipeline.PipelineTools

/**
 * Opinionated configurations are now under vars/configs.groovy.
 * WDKConfig.fromConfigMap is replaced by configs().unityWDK(...)
 */
@Deprecated
class WDKConfig extends Config {

    final String buildLabel

    static List<Platform> collectWDKPlatforms(List unityVerObjs, String buildLabel, Map configMap) {
        def index = 0
        return unityVerObjs.collect { Object unityVersionObj ->
            def buildVersion = BuildVersion.parse(unityVersionObj)
            def platform = Platform.forWDK(buildVersion, buildLabel, configMap, index == 0)
            index++
            return platform
        }
    }

    static WDKConfig fromConfigMap(String buildLabel, Map configMap, Object jenkinsScript) {
        configMap.unityVersions = configMap.unityVersions ?: []
        def platforms = collectWDKPlatforms(configMap.unityVersions as List, buildLabel, configMap)
        if (platforms.isEmpty()) throw new IllegalArgumentException("Please provide at least one unity version.")

        def baseConfig = BaseConfig.fromConfigMap(configMap, jenkinsScript)

        return new WDKConfig(platforms, baseConfig, buildLabel)
    }

    WDKConfig(List<Platform> platforms, BaseConfig baseConfig, String buildLabel) {
        super(baseConfig, platforms)
        this.buildLabel = buildLabel
    }

    @Override
    DockerArgs getDockerArgs() {
        return null
    }
}


