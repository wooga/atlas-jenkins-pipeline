package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.check.steps.GradleSteps
import net.wooga.jenkins.pipeline.check.steps.Step
import net.wooga.jenkins.pipeline.config.CheckArgs
import net.wooga.jenkins.pipeline.config.PipelineConventions
import net.wooga.jenkins.pipeline.config.Platform

class JSChecks {

    final CheckCreator checkCreator
    final GradleSteps steps

    JSChecks(CheckCreator checkCreator, GradleSteps steps) {
        this.checkCreator = checkCreator
        this.steps = steps
    }

    Map<String, Closure> gradleCheckWithCoverage(Platform[] platforms, CheckArgs checkArgs, PipelineConventions conventions) {
        def baseTestStep = steps.defaultGradleTestStep(conventions.checkTask)
        def baseAnalysisStep = steps.staticAnalysis(conventions, checkArgs.metadata, checkArgs.sonarqube, checkArgs.coveralls)
        return parallel(platforms,
                baseTestStep.wrappedBy(checkArgs.testWrapper),
                baseAnalysisStep.wrappedBy(checkArgs.analysisWrapper), conventions)
    }

    Closure check(Platform platform, Step testStep, Step analysisStep,
                  PipelineConventions conventions = PipelineConventions.standard) {
        return checkCreator.junitCheck(conventions.workingDir, platform, testStep, analysisStep)
    }

    Map<String, Closure> parallel(Platform[] platforms, Closure checkStep,
                                  PipelineConventions conventions = PipelineConventions.standard) {
        return parallel(platforms, new Step(checkStep), conventions)
    }

    Map<String, Closure> parallel(Platform[] platforms, Step checkStep,
                                  PipelineConventions conventions = PipelineConventions.standard) {
        return parallel(platforms, checkStep, new Step({}), conventions)
    }

    Map<String, Closure> parallel(Platform[] platforms, Closure testStep, Closure analysisStep,
                                  PipelineConventions conventions = PipelineConventions.standard) {
        parallel(platforms, new Step(testStep), new Step(analysisStep), conventions)
    }

    Map<String, Closure> parallel(Platform[] platforms, Step testStep, Step analysisStep,
                                  PipelineConventions conventions = PipelineConventions.standard) {
        return platforms.collectEntries { platform ->
            String parallelStepName = "${conventions.javaParallelPrefix}${platform.name}"
            return [(parallelStepName): check(platform, testStep, analysisStep, conventions)]
        }
    }
}
