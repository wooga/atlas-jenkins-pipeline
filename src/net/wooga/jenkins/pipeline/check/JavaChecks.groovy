package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.config.*

class JavaChecks {

    final Object jenkins
    final CheckCreator checkCreator
    final GradleSteps steps

    JavaChecks(Object jenkinsScript, CheckCreator checkCreator, GradleSteps steps) {
        this.jenkins = jenkinsScript
        this.checkCreator = checkCreator
        this.steps = steps
    }

    Closure check(Platform platform, Step testStep, Step analysisStep) {
        return checkCreator.javaChecks(platform, testStep, analysisStep)
    }

    Map<String, Closure> parallel(Platform[] platforms, Closure checkStep,
                                  PipelineConventions conventions = PipelineConventions.standard) {
        return parallel(platforms, new Step(checkStep), conventions)
    }

    Map<String, Closure> parallel(Platform[] platforms, Step checkStep,
                                  PipelineConventions conventions = PipelineConventions.standard) {
        return platforms.collectEntries { platform ->
            [("${conventions.javaParallelPrefix}${platform.name}".toString()): checkStep.asRunnable(platform)]
        }
    }

    Map<String, Closure> parallel(Platform[] platforms, Closure testStep, Closure analysisStep,
                                  PipelineConventions conventions = PipelineConventions.standard) {
        parallel(platforms, new Step(testStep), new Step(analysisStep), conventions)
    }

    Map<String, Closure> parallel(Platform[] platforms, Step testStep, Step analysisStep,
                                  PipelineConventions conventions = PipelineConventions.standard) {
        return platforms.collectEntries { platform ->
            String parallelStepName = "${conventions.javaParallelPrefix}${platform.name}"
            return [(parallelStepName): check(platform, testStep, analysisStep)]
        }
    }

    Map<String, Closure> gradleCheckWithCoverage(Platform[] platforms, CheckArgs checkArgs, PipelineConventions conventions) {
        def baseTestStep = steps.defaultJavaTestStep(conventions.checkTask)
        def baseAnalysisStep = steps.defaultJavaAnalysisStep(conventions, checkArgs.metadata, checkArgs.sonarqube, checkArgs.coveralls)

        return parallel(platforms,
                baseTestStep.wrappedBy(checkArgs.testWrapper),
                baseAnalysisStep.wrappedBy(checkArgs.analysisWrapper), conventions)
    }

//    static Closure defaultJavaTestStep(String checkTask) {
//        return { Platform plat, Gradle gradle ->
//            gradle.wrapper(checkTask)
//        }
//    }
//
//    static Closure defaultJavaAnalysisStep(PipelineConventions conventions,
//                                           JenkinsMetadata metadata,
//                                           Sonarqube sonarqube,
//                                           Coveralls coveralls) {
//        return { Platform plat, Gradle gradle ->
//            def branchName = metadata.isPR() ? null : metadata.branchName
//            gradle.wrapper(conventions.jacocoTask)
//            sonarqube.maybeRun(gradle, conventions.sonarqubeTask,  branchName)
//            coveralls.maybeRun(gradle, conventions.coverallsTask)
//        }
//    }
}
