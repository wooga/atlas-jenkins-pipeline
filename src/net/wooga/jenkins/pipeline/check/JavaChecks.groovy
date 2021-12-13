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

    Map<String, Closure> javaCoverage(JavaConfig config,
                                      PipelineConventions conventions = PipelineConventions.standard,
                                      Sonarqube sonarqube = null,
                                      Coveralls coveralls = null) {
        sonarqube = sonarqube?: new Sonarqube(conventions.sonarqubeTask)
        coveralls = coveralls?: new Coveralls(jenkins, conventions.coverallsTask)
        def testStep = getJavaTestStep(conventions.checkTask)
        def analysisStep = getJavaAnalysisStep(config, conventions.jacocoTask, sonarqube, coveralls)
        return parallel(config.platforms, testStep, analysisStep, conventions)
    }

    Closure getJavaTestStep(String checkTask = PipelineConventions.standard.checkTask) {
        return {_, Gradle gradle ->
            jenkins.checkout(jenkins.scm)
            gradle.wrapper(checkTask)
        }
    }

    Closure getJavaAnalysisStep(JavaConfig config,
                                String jacocoTask = PipelineConventions.standard.jacocoTask,
                                Sonarqube sonarqube = new Sonarqube(PipelineConventions.standard.sonarqubeTask),
                                Coveralls coveralls = new Coveralls(jenkins, PipelineConventions.standard.coverallsTask)) {
        return {_, Gradle gradle ->
            def branchName = config.metadata.isPR()? null : config.metadata.branchName
            gradle.wrapper(jacocoTask)
            sonarqube.runGradle(gradle, config.sonarArgs, branchName)
            coveralls.runGradle(gradle, config.coverallsToken)
        }
    }
}
