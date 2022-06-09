import net.wooga.jenkins.pipeline.BuildVersion
import net.wooga.jenkins.pipeline.config.BaseConfig
import net.wooga.jenkins.pipeline.config.Config
import net.wooga.jenkins.pipeline.config.Platform



def call(String type="") {
    def configs = [
        java: this.&forJava,
        unityWDK: this.&forUnityWDKs,
        jsWDK: this.&forJSWDKs
    ]
    return configs[type]?: configs
}

static List<Platform> collectPlatform(Map configMap, List<String> platformNames) {
    def index = 0
    return platformNames.collect { String platformName ->
        def platform = Platform.forJava(platformName, configMap, index == 0)
        index++
        return platform
    }
}

Config forJava(Map config, Object jenkinsScript) {
    config.platforms = config.platforms ?: ['macos','windows']
    def platforms = collectPlatform(config, config.platforms as List<String>)
    def baseConfig = BaseConfig.fromConfigMap(config, jenkinsScript)

    return new Config(baseConfig, platforms)
}

List<Platform> collectJSWDKPlatforms(Map configMap, List<String> platformNames) {
    def index = 0
    return platformNames.collect { String platformName ->
        def platform = Platform.forJS(platformName, configMap, index == 0)
        index++
        return platform
    }
}

Config forJSWDKs(Map configMap, Object jenkinsScript) {
    configMap.platforms = configMap.platforms ?: ['macos']
    def baseConfig = BaseConfig.fromConfigMap(configMap, jenkinsScript)
    def platforms = collectJSWDKPlatforms(configMap, configMap.platforms as List<String>)

    return new Config(baseConfig, platforms)
}

List<Platform> collectWDKPlatforms(List unityVerObjs, String buildLabel, Map configMap) {
    def index = 0
    return unityVerObjs.collect { Object unityVersionObj ->
        def buildVersion = BuildVersion.parse(unityVersionObj)
        def platform = Platform.forWDK(buildVersion, buildLabel, configMap, index == 0)
        index++
        return platform
    }
}

Config forUnityWDKs(String buildLabel, Map configMap, Object jenkinsScript) {
    configMap.unityVersions = configMap.unityVersions ?: []
    def platforms = collectWDKPlatforms(configMap.unityVersions as List, buildLabel, configMap)
    if (platforms.isEmpty()) {
        error("Please provide at least one unity version.")
    }

    def baseConfig = BaseConfig.fromConfigMap(configMap, jenkinsScript)
    return new Config(baseConfig, platforms)
}