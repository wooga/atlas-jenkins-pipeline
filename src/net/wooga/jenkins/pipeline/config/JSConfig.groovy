package net.wooga.jenkins.pipeline.config

/**
 * Opinionated configurations are now under vars/configs.groovy.
 * * WDKConfig.fromConfigMap is replaced by configs().jsWDK(...)
 */
@Deprecated
class JSConfig extends Config {

    @Deprecated
    static List<Platform> collectPlatforms(Map configMap, List<String> platformNames) {
        def index = 0
        return platformNames.collect { String platformName ->
            def platform = Platform.forJS(platformName, configMap, index == 0)
            index++
            return platform
        }
    }

    @Deprecated
    static JSConfig fromConfigMap(Map configMap, Object jenkinsScript) {
        configMap.platforms = configMap.platforms ?: ['macos']
        def baseConfig = BaseConfig.fromConfigMap(configMap, jenkinsScript)
        def platforms = collectPlatforms(configMap, configMap.platforms as List<String>)

        return new JSConfig(baseConfig, platforms)
    }

    JSConfig(BaseConfig baseConfig, List<Platform> platforms) {
        super(baseConfig, platforms)
    }
}
