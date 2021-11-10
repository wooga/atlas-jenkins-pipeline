package scripts

import net.wooga.jenkins.pipeline.config.Config
import net.wooga.jenkins.pipeline.check.Checks
import net.wooga.jenkins.pipeline.config.WDKConfig
import net.wooga.jenkins.pipeline.model.Gradle

def javaCoverage(Map configMap, Map jenkinsVars) {
    def config = Config.fromConfigMap(configMap, jenkinsVars)
    return Checks.create(this, Gradle.fromJenkins(this, null, false),
            config.dockerArgs, config.metadata.buildNumber).javaCoverage(config)
}

def wdkCoverage(String buildLabel, Map configMap, Map jenkinsVars, String releaseType, String releaseScope, String stashKey) {
    def config = WDKConfig.fromConfigMap(buildLabel, configMap, jenkinsVars)
    def check = Checks.create(this, Gradle.fromJenkins(this, null, false), null, BUILD_NUMBER as int)
    return check.wdkCoverage(config, releaseType, releaseScope, stashKey)

}

def simple(Map configMap, Map jenkinsVars, Closure checkCls, Closure analysisCls) {
    def config = Config.fromConfigMap(configMap, jenkinsVars)
    return Checks.create(this, Gradle.fromJenkins(this, null, false),
            config.dockerArgs, config.metadata.buildNumber).simple(config, checkCls, analysisCls)
}

def simpleWDK(String label, Map configMap, Map jenkinsVars, Closure checkCls, Closure analysisCls) {
    def config = WDKConfig.fromConfigMap(label, configMap, jenkinsVars)
    def check = Checks.create(this, Gradle.fromJenkins(this, null, false), null, BUILD_NUMBER as int)
    return check.simple(config, checkCls, analysisCls)
}

private Checks checks(Config config) {
    return Checks.create(this, Gradle.fromJenkins(this, null, false),
            config.dockerArgs, config.metadata.buildNumber)
}

def call(Map configMap, Map jenkinsVars) {
    def config = Config.fromConfigMap(configMap, jenkinsVars)
    return Checks.create(this, Gradle.fromJenkins(this, null, false), config.dockerArgs, config.metadata.buildNumber)
}
def call() {
    return
}
