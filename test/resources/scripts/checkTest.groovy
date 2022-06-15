package scripts

import net.wooga.jenkins.pipeline.check.steps.Step
import net.wooga.jenkins.pipeline.config.Config

def javaCoverage(Map configMap) {
    def config = configs().java(configMap, this) as Config
    Step checkTemplate = javaCheckTemplate(config.pipelineTools.checks, config.checkArgs, config.conventions)
    return parallelize(checkTemplate, config.platforms, config.conventions.javaParallelPrefix)
}

def parallel(Map configMap, Closure checkCls, Closure analysisCls) {
    def config = configs().java(configMap, this) as Config
    def checkStep = config.pipelineTools.checks.enclosedSimpleCheck(
            new Step(checkCls).wrappedBy(config.checkArgs.testWrapper),
            new Step(analysisCls).wrappedBy(config.checkArgs.analysisWrapper),
            { ex -> throw ex },{  })
    return parallelize(checkStep, config.platforms, config.conventions.javaParallelPrefix)
}

def wdkCoverage(String buildLabel, Map configMap, String releaseType, String releaseScope) {
    def config = configs().unityWDK(buildLabel, configMap, this) as Config
    Step checkTemplate = wdkCheckTemplate(config.pipelineTools.checks, config.checkArgs,
            releaseType, releaseScope, config.conventions)
    return parallelize(checkTemplate, config.platforms, config.conventions.wdkParallelPrefix)
}

def simpleWDK(String buildLabel, Map configMap, Closure checkCls, Closure analysisCls) {
    def config = configs().unityWDK(buildLabel, configMap, this) as Config
    def checkStep = config.pipelineTools.checks.enclosedSimpleCheck(
            new Step(checkCls).wrappedBy(config.checkArgs.testWrapper),
            new Step(analysisCls).wrappedBy(config.checkArgs.analysisWrapper),
            { ex -> throw ex },
            {  })
    return parallelize(checkStep, config.platforms, config.conventions.wdkParallelPrefix)
}
