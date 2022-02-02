package scripts

import net.wooga.jenkins.pipeline.check.WDKChecksParams
import net.wooga.jenkins.pipeline.config.JavaConfig
import net.wooga.jenkins.pipeline.check.Checks
import net.wooga.jenkins.pipeline.config.PipelineConfig
import net.wooga.jenkins.pipeline.config.WDKConfig
import net.wooga.jenkins.pipeline.config.PipelineConventions

def javaCoverage(Map configMap, Map jenkinsVars) {
    def config = JavaConfig.fromConfigMap(configMap, jenkinsVars)
    return Checks.forJavaPipelines(this, config).javaCoverage(config)
}

def wdkCoverage(String buildLabel, Map configMap, Map jenkinsVars, String releaseType, String releaseScope, String stashKey) {
    def config = WDKConfig.fromConfigMap(buildLabel, configMap, jenkinsVars)
    def check = Checks.forWDKPipelines(this, config)
    return check.wdkCoverage(config, releaseType, releaseScope) { WDKChecksParams it ->
        it.setupStashId = stashKey
    }
}

def parallel(Map configMap, Map jenkinsVars, Closure checkCls, Closure analysisCls) {
    def config = JavaConfig.fromConfigMap(configMap, jenkinsVars)
    def checks = Checks.forJavaPipelines(this, config)
    return checks.parallel(config.platforms, checkCls, analysisCls)
}

def simpleWDK(String label, Map configMap, Map jenkinsVars, Closure checkCls, Closure analysisCls) {
    def config = WDKConfig.fromConfigMap(label, configMap, jenkinsVars)
    def check = Checks.forWDKPipelines(this, config)
    return check.parallel(config.unityVersions, checkCls, analysisCls)
}

def getConfig(Map configMap, Map jenkinsVars, String label=null) {
    if(configMap.containsKey("unityVersions") && label != null) {
        return WDKConfig.fromConfigMap(label, configMap, jenkinsVars)
    } else {
        return JavaConfig.fromConfigMap(configMap, jenkinsVars)
    }

}

def call(PipelineConfig config) {
    if(config instanceof  WDKConfig) {
        return Checks.forWDKPipelines(this, config)
    } else if(config instanceof JavaConfig) {
        return Checks.forJavaPipelines(this, config)
    }
}
