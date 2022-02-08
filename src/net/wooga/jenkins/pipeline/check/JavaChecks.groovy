package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.config.*
import net.wooga.jenkins.pipeline.model.Gradle


class JavaChecks {

    final Object jenkins
    final CheckCreator checkCreator
    final Gradle gradle

    JavaChecks(Object jenkinsScript, CheckCreator checkCreator, Gradle gradle) {
        this.jenkins = jenkinsScript
        this.checkCreator = checkCreator
        this.gradle = gradle
    }

    Map<String, Closure> parallel(Platform[] platforms, Closure checkStep,
                                  PipelineConventions conventions = PipelineConventions.standard) {
        return platforms.collectEntries { platform ->
            [("${conventions.javaParallelPrefix}${platform.name}".toString()): { checkStep(platform, gradle) }]
        }
    }

    Map<String, Closure> parallel(Platform[] platforms, Closure testStep, Closure analysisStep,
                                  PipelineConventions conventions = PipelineConventions.standard) {
        def curriedTestStep = { plat -> testStep.call(plat, gradle) }
        def curriedAnalysisStep = { plat -> analysisStep(plat, gradle) }
        return platforms.collectEntries { platform ->
            def checkStep = checkCreator.javaChecks(platform, curriedTestStep, curriedAnalysisStep)
            return [("${conventions.javaParallelPrefix}${platform.name}".toString()): checkStep]
        }
    }

    Map<String, Closure> gradleCheckWithCoverage(Platform[] platforms, CheckArgs checkArgs, PipelineConventions conventions) {
        def baseTestStep = defaultJavaTestStep(conventions.checkTask)
        def baseAnalysisStep = defaultJavaAnalysisStep(conventions, checkArgs.metadata, checkArgs.sonarqube, checkArgs.coveralls)

        return parallel(platforms,
                { plat, gradle -> checkArgs.testWrapper(baseTestStep, plat, gradle) },
                { plat, gradle -> checkArgs.analysisWrapper(baseAnalysisStep, plat, gradle) }, conventions)
    }

    static Closure defaultJavaTestStep(String checkTask) {
        return { Platform plat, Gradle gradle ->
            gradle.wrapper(checkTask)
        }
    }

    static Closure defaultJavaAnalysisStep(PipelineConventions conventions,
                                           JenkinsMetadata metadata,
                                           Sonarqube sonarqube,
                                           Coveralls coveralls) {
        return { Platform plat, Gradle gradle ->
            def branchName = metadata.isPR() ? null : metadata.branchName
            gradle.wrapper(conventions.jacocoTask)
            sonarqube.maybeRun(gradle, conventions.sonarqubeTask,  branchName)
            coveralls.maybeRun(gradle, conventions.coverallsTask)
        }
    }
}
