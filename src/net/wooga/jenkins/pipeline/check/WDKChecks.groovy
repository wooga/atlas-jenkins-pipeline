package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.config.*
import net.wooga.jenkins.pipeline.model.Gradle


class WDKChecks {

    final Object jenkins
    final CheckCreator checkCreator
    final Gradle gradle

    WDKChecks(Object jenkinsScript, CheckCreator checkCreator, Gradle gradle) {
        this.jenkins = jenkinsScript
        this.checkCreator = checkCreator
        this.gradle = gradle
    }

    Map<String, Closure> parallel(UnityVersionPlatform[] wdkPlatforms, Closure checkStep,
                                  PipelineConventions conventions = PipelineConventions.standard) {
        return wdkPlatforms.collectEntries { wdkPlatform ->
            [("${conventions.wdkParallelPrefix}${wdkPlatform.platform.name}".toString()): checkStep.curry(wdkPlatform, gradle)]
        }
    }

    Map<String, Closure> parallel(UnityVersionPlatform[] wdkPlatforms, Closure testStep, Closure analysisStep,
                                  PipelineConventions conventions = PipelineConventions.standard) {
        return wdkPlatforms.collectEntries { wdkPlatform ->
            def checkStep = checkCreator.csWDKChecks(wdkPlatform,
                    { plat -> testStep(plat, gradle) }, { plat -> analysisStep(plat, gradle) })
            return [("${conventions.wdkParallelPrefix}${wdkPlatform.platform.name}".toString()): checkStep]
        }
    }

    Map<String, Closure> wdkCoverage(WDKConfig config, String releaseType, String releaseScope,
                                     String setupStashId = "setup_w",
                                     PipelineConventions conventions = PipelineConventions.standard) {
        def testStep = getWDKTestStep(releaseType, releaseScope, setupStashId, conventions.checkTask)
        def analysisStep = getWDKAnalysisStep(config, conventions.wdkCoberturaFile, new Sonarqube(conventions.sonarqubeTask))
        return parallel(config.unityVersions, testStep, analysisStep, conventions)
    }

    Closure getWDKTestStep(String releaseType, String releaseScope, String setupStashId = "setup_w",
                           String checkTask = PipelineConventions.standard.checkTask) {
        return { UnityVersionPlatform versionPlat, Gradle gradle ->
            jenkins.dir(versionPlat.directoryName) {
                jenkins.checkout(jenkins.scm)
                jenkins.unstash setupStashId
                gradle.wrapper("-Prelease.stage=${releaseType.trim()} -Prelease.scope=${releaseScope.trim()} ${checkTask}")
            }
        }
    }

    Closure getWDKAnalysisStep(WDKConfig config,
                               String wdkCoberturaFile = PipelineConventions.standard.wdkCoberturaFile,
                               Sonarqube sonarqube = new Sonarqube(PipelineConventions.standard.sonarqubeTask)) {
        return { UnityVersionPlatform versionPlat, Gradle gradle ->
            jenkins.dir(versionPlat.directoryName) {
                def branchName = config.metadata.isPR() ? null : config.metadata.branchName
                sonarqube.runGradle(gradle, config.sonarArgs, branchName)
            }
            def coberturaAdapter = jenkins.istanbulCoberturaAdapter(wdkCoberturaFile)
            jenkins.publishCoverage adapters: [coberturaAdapter],
                    sourceFileResolver: jenkins.sourceFiles('STORE_LAST_BUILD')
        }
    }

}
