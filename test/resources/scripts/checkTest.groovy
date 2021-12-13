package scripts

import net.wooga.jenkins.pipeline.config.JavaConfig
import net.wooga.jenkins.pipeline.check.Checks
import net.wooga.jenkins.pipeline.config.WDKConfig

def javaCoverage(Map configMap, Map jenkinsVars) {
    def config = JavaConfig.fromConfigMap(configMap, jenkinsVars)
    return Checks.forJavaPipelines(this, config).javaCoverage(config)
}

def wdkCoverage(String buildLabel, Map configMap, Map jenkinsVars, String releaseType, String releaseScope, String stashKey) {
    def config = WDKConfig.fromConfigMap(buildLabel, configMap, jenkinsVars)
    def check = Checks.forWDKPipelines(this, config)
    return check.wdkCoverage(config, releaseType, releaseScope, stashKey)

}

def parallel(Map configMap, Map jenkinsVars, Closure checkCls, Closure analysisCls) {
    def config = JavaConfig.fromConfigMap(configMap, jenkinsVars)
    def checks = Checks.forJavaPipelines(this, config)
    return checks.parallel(config.platforms, checkCls, analysisCls)
}

def simpleWDK(String label, Map configMap, Map jenkinsVars, Closure checkCls, Closure analysisCls) {
    def config = WDKConfig.fromConfigMap(label, configMap, jenkinsVars)
    def check = Checks.forWDKPipelines(this, config)
    return check.simple(config, checkCls, analysisCls)
}

def call(Map configMap, Map jenkinsVars) {
    def config = JavaConfig.fromConfigMap(configMap, jenkinsVars)
    if(configMap.containsKey("unityVersions")) {
        return Checks.forWDKPipelines(this, config)
    } else {
        return Checks.forJavaPipelines(this, config)
    }
}
