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

    Map<String, Closure> javaCoverage(JavaConfig config, Closure overrides) {
        JavaChecksParams defaults = new JavaChecksParams()
        Closure cloned = overrides.clone() as Closure
        cloned.setDelegate(defaults)
        cloned(defaults)
        return javaCoverage(config, defaults)
    }

    Map<String, Closure> javaCoverage(JavaConfig config, JavaChecksParams params = new JavaChecksParams()) {
        def conventions = params.conventions
        def sonarqube = params.sonarqubeOrDefault()
        def coveralls = params.coverallsOrDefault(jenkins)
        def baseTestStep = defaultJavaTestStep(conventions.checkTask)
        def baseAnalysisStep = defaultJavaAnalysisStep(config, conventions.jacocoTask, sonarqube, coveralls)

        return parallel(config.platforms,
                { plat, gradle -> params.testWrapper(baseTestStep, plat, gradle) },
                { plat, gradle -> params.analysisWrapper(baseAnalysisStep, plat, gradle) }, conventions)
    }

    Closure defaultJavaTestStep(String checkTask = PipelineConventions.standard.checkTask) {
        return { Platform plat, Gradle gradle ->
            gradle.wrapper(checkTask)
        }
    }

    Closure defaultJavaAnalysisStep(JavaConfig config,
                                    String jacocoTask = PipelineConventions.standard.jacocoTask,
                                    Sonarqube sonarqube = new Sonarqube(PipelineConventions.standard.sonarqubeTask),
                                    Coveralls coveralls = new Coveralls(jenkins, PipelineConventions.standard.coverallsTask)) {
        return { Platform plat, Gradle gradle ->
            def branchName = config.metadata.isPR() ? null : config.metadata.branchName
            gradle.wrapper(jacocoTask)
            sonarqube.runGradle(gradle, config.sonarArgs, branchName)
            coveralls.runGradle(gradle, config.coverallsToken)
        }
    }
}
