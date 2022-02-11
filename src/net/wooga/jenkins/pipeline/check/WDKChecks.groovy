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

    Map<String, Closure> wdkCoverage(UnityVersionPlatform[] unityPlatforms, String releaseType, String releaseScope,
                                     CheckArgs checkArgs, PipelineConventions conventions) {
        def baseTestStep = getWDKTestStep(releaseType, releaseScope, conventions.wdkSetupStashId, conventions.checkTask)
        def baseAnalysisStep = getWDKAnalysisStep(conventions, checkArgs.metadata, checkArgs.sonarqube)

        return parallel(unityPlatforms,
                {versionPlat, gradle -> checkArgs.testWrapper(baseTestStep, versionPlat, gradle) },
                {versionPlat, gradle -> checkArgs.analysisWrapper(baseAnalysisStep, versionPlat, gradle) }, conventions)
    }

    Closure getWDKTestStep(String releaseType, String releaseScope, String setupStashId, String checkTask) {
        return { Platform platform, Gradle gradle ->
            jenkins.unstash setupStashId
            gradle.wrapper("-Prelease.stage=${releaseType.trim()} -Prelease.scope=${releaseScope.trim()} ${checkTask}")
        }
    }

    Closure getWDKAnalysisStep(PipelineConventions conventions,
                               JenkinsMetadata metadata,
                               Sonarqube sonarqube) {
        return { Platform platform, Gradle gradle ->
            def branchName = metadata.isPR() ? null : metadata.branchName
            sonarqube.maybeRun(gradle, conventions.sonarqubeTask, branchName)
            def coberturaAdapter = jenkins.istanbulCoberturaAdapter(conventions.wdkCoberturaFile)
            jenkins.publishCoverage adapters: [coberturaAdapter],
                    sourceFileResolver: jenkins.sourceFiles('STORE_LAST_BUILD')
        }
    }
}
