package net.wooga.jenkins.pipeline.config

/**
 * Opinionated configurations are now under vars/configs.groovy.
 * WDKConfig.fromConfigMap is replaced by configs().java(...)
 */
@Deprecated
class JavaConfig extends Config {

    @Deprecated
    static List<Platform> collectPlatform(Map configMap, List<String> platformNames) {
        def index = 0
        return platformNames.collect { String platformName ->
            def platform = Platform.forJava(platformName, configMap, index == 0)
            index++
            return platform
        }
    }

    @Deprecated
    static JavaConfig fromConfigMap(Map config, Object jenkinsScript) {
        config.platforms = config.platforms ?: ['macos','windows']
        def platforms = collectPlatform(config, config.platforms as List<String>)
        def baseConfig = BaseConfig.fromConfigMap(config, jenkinsScript)

        return new JavaConfig(baseConfig, platforms)
    }

    JavaConfig(BaseConfig baseConfig, List<Platform> platforms) {
        super(baseConfig, platforms)
    }
}
