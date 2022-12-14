package scripts

import net.wooga.jenkins.pipeline.config.JavaConfig
import net.wooga.jenkins.pipeline.config.WDKConfig

def javaCoverage(Map configMap) {
    def config = JavaConfig.fromConfigMap(configMap, this)
    def checks = config.pipelineTools.checks.forJavaPipelines()
    return checks.gradleCheckWithCoverage(config.platforms, config.checkArgs, config.conventions)
}

def parallel(Map configMap, Closure checkCls, Closure analysisCls) {
    def config = JavaConfig.fromConfigMap(configMap, this)
    def checks = config.pipelineTools.checks.forJavaPipelines()
    return checks.parallel(config.platforms, checkCls, analysisCls, config.conventions)
}


def wdkCoverage(Map configMap, String releaseType, String releaseScope) {
    def config = WDKConfig.fromConfigMap(configMap, this)
    def checks = config.pipelineTools.checks.forWDKPipelines()
    return checks.wdkCoverage(config.unityVersions, releaseType, releaseScope, config.checkArgs, config.conventions)
}

def simpleWDK(Map configMap, Closure checkCls, Closure analysisCls) {
    def config = WDKConfig.fromConfigMap(configMap, this)
    def checks = config.pipelineTools.checks.forWDKPipelines()
    return checks.parallel(config.unityVersions, checkCls, analysisCls)
}
