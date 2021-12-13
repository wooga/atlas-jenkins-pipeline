package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.config.*
import net.wooga.jenkins.pipeline.model.Gradle


class WDKChecks {

    final Object jenkins
    final CheckCreator checkCreator

    final Gradle gradle
    final Sonarqube sonarqube
    final Coveralls coveralls

    WDKChecks(Object jenkinsScript, CheckCreator checkCreator, Gradle gradle, Sonarqube sonarqube, Coveralls coveralls) {
        this.jenkins = jenkinsScript
        this.checkCreator = checkCreator
        this.gradle = gradle
        this.sonarqube = sonarqube
        this.coveralls = coveralls
    }

    Map<String, Closure> simple(WDKConfig config, Closure testStep,
                                Closure analysisStep) {
        return parallel(config.unityVersions, testStep, analysisStep)
    }

    Map<String, Closure> parallel(UnityVersionPlatform[] wdkPlatforms, Closure checkStep) {
        return wdkPlatforms.collectEntries { wdkPlatform ->
            [("check Unity-${wdkPlatform.platform.name}".toString()): checkStep.curry(wdkPlatform, gradle)]
        }
    }

    Map<String, Closure> parallel(UnityVersionPlatform[] wdkPlatforms, Closure testStep, Closure analysisStep) {
        return wdkPlatforms.collectEntries { wdkPlatform ->
            def checkStep = checkCreator.csWDKChecks(wdkPlatform,
                    { plat -> testStep(plat, gradle) }, { plat -> analysisStep(plat, gradle) })
            return [("check Unity-${wdkPlatform.platform.name}".toString()): checkStep]
        }
    }

    Map<String, Closure> wdkCoverage(WDKConfig config, String releaseType, String releaseScope, String setupStashId = "setup_w") {
        def testStep = getWDKTestStep(releaseType, releaseScope, setupStashId)
        def analysisStep = getWDKAnalysisStep(config, sonarqube)
        return parallel(config.unityVersions, testStep, analysisStep)
    }

    Closure getWDKTestStep(String releaseType, String releaseScope, String setupStashId = "setup_w") {
        return { UnityVersionPlatform versionPlat, Gradle gradle ->
            jenkins.dir(versionPlat.directoryName) {
                jenkins.checkout(jenkins.scm)
                jenkins.unstash setupStashId
                gradle.wrapper("-Prelease.stage=${releaseType.trim()} " +
                        "-Prelease.scope=${releaseScope.trim()} " +
                        "check")
            }
        }
    }

    Closure getWDKAnalysisStep(WDKConfig config, Sonarqube sonarqube = new Sonarqube()) {
        return { UnityVersionPlatform versionPlat, Gradle gradle ->
            jenkins.dir(versionPlat.directoryName) {
                def branchName = config.metadata.isPR() ? null : config.metadata.branchName
                sonarqube.runGradle(gradle, config.sonarArgs, branchName)
            }
            def coberturaAdapter = jenkins.istanbulCoberturaAdapter('**/codeCoverage/Cobertura.xml')
            jenkins.publishCoverage adapters: [coberturaAdapter],
                    sourceFileResolver: jenkins.sourceFiles('STORE_LAST_BUILD')
        }
    }

}
