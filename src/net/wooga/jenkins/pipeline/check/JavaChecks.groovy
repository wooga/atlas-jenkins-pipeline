package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.config.*
import net.wooga.jenkins.pipeline.model.Gradle

import java.util.function.BiConsumer

class JavaChecks {

    final Object jenkins
    final CheckCreator checkCreator

    final Gradle gradle
    final Sonarqube sonarqube
    final Coveralls coveralls

    JavaChecks(Object jenkinsScript, CheckCreator checkCreator,
               Gradle gradle, Sonarqube sonarqube, Coveralls coveralls) {
        this.jenkins = jenkinsScript
        this.checkCreator = checkCreator
        this.gradle = gradle
        this.sonarqube = sonarqube
        this.coveralls = coveralls
    }

    Map<String, Closure> parallel(Platform[] platforms, Closure checkStep) {
        return platforms.collectEntries { platform ->
            [("check ${platform.name}".toString()): { checkStep(platform, gradle) }]
        }
    }


    Map<String, Closure> parallel(Platform[] platforms, Closure testStep, Closure analysisStep) {
        def curriedTestStep = { plat -> testStep.call(plat, gradle) }
        def curriedAnalysisStep = { plat -> analysisStep(plat, gradle) }
        return platforms.collectEntries { platform ->
            def checkStep = checkCreator.javaChecks(platform, curriedTestStep, curriedAnalysisStep)
            return [("check ${platform.name}".toString()): checkStep]
        }
    }

    Map<String, Closure> simple(JavaConfig config, Closure testStep, Closure analysisStep) {
        return parallel(config.platforms, testStep, analysisStep)
    }

    Map<String, Closure> javaCoverage(JavaConfig config) {
        def testStep = javaTestStep(config) as Closure
        def analysisStep = javaAnalysisStep(config, sonarqube, coveralls) as Closure
        return parallel(config.platforms, testStep, analysisStep)
    }

    Closure javaTestStep(JavaConfig config) {
        return {_, Gradle gradle ->
            jenkins.checkout(jenkins.scm)
            gradle.wrapper("check")
        }
    }

    Closure javaAnalysisStep(JavaConfig config,
                                Sonarqube sonarqube = new Sonarqube(),
                                Coveralls coveralls = new Coveralls(jenkins)) {
        return {_, Gradle gradle ->
            def branchName = config.metadata.isPR()? null : config.metadata.branchName
            gradle.wrapper("jacocoTestReport")
            sonarqube.runGradle(gradle, config.sonarArgs, branchName)
            coveralls.runGradle(gradle, config.coverallsToken)
        }
    }
}
