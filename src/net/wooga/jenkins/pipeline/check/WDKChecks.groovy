package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.check.steps.GradleSteps
import net.wooga.jenkins.pipeline.check.steps.Step
import net.wooga.jenkins.pipeline.config.*


class WDKChecks {

    final CheckCreator checkCreator
    final GradleSteps steps

    WDKChecks(CheckCreator checkCreator, GradleSteps steps) {
        this.checkCreator = checkCreator
        this.steps = steps
    }

    Map<String, Closure> wdkCoverage(UnityVersionPlatform[] unityVersions, String releaseType, String releaseScope,
                                     CheckArgs checkArgs, PipelineConventions conventions) {
        def baseTestStep = steps.wdkTestStep(releaseType, releaseScope, conventions.wdkSetupStashId, conventions.checkTask)
        def baseAnalysisStep = steps.wdkAnalysisStep(conventions, checkArgs.metadata, checkArgs.sonarqube)

        return parallel(unityVersions,
                        baseTestStep.wrappedBy(checkArgs.testWrapper),
                        baseAnalysisStep.wrappedBy(checkArgs.analysisWrapper),
                        conventions)
    }

    Closure check(UnityVersionPlatform platform, Step testStep, Step analysisStep) {
        return checkCreator.csWDKChecks(platform, testStep, analysisStep)
    }

    Map<String, Closure> parallel(UnityVersionPlatform[] wdkPlatforms, Closure checkStep,
                                  PipelineConventions conventions = PipelineConventions.standard) {
        return parallel(wdkPlatforms, new Step(checkStep), conventions)
    }

    Map<String, Closure> parallel(UnityVersionPlatform[] wdkPlatforms, Step checkStep,
                                  PipelineConventions conventions = PipelineConventions.standard) {
        return wdkPlatforms.collectEntries { wdkPlatform ->
            [("${conventions.wdkParallelPrefix}${wdkPlatform.platform.name}".toString()): checkStep.pack(wdkPlatform.platform)]
        }
    }

    Map<String, Closure> parallel(UnityVersionPlatform[] wdkPlatforms, Closure testStep, Closure analysisStep,
                                  PipelineConventions conventions = PipelineConventions.standard) {
        return parallel(wdkPlatforms, new Step(testStep), new Step(analysisStep), conventions)
    }

    Map<String, Closure> parallel(UnityVersionPlatform[] wdkPlatforms, Step testStep, Step analysisStep,
                                  PipelineConventions conventions = PipelineConventions.standard) {
        return wdkPlatforms.collectEntries { wdkPlatform ->
            def checkStep = checkCreator.csWDKChecks(wdkPlatform, testStep, analysisStep)
            return [("${conventions.wdkParallelPrefix}${wdkPlatform.platform.name}".toString()): checkStep]
        }
    }

}
