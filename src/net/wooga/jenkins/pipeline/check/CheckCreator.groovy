package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.config.Platform
import net.wooga.jenkins.pipeline.config.UnityVersionPlatform

class CheckCreator {
    final Object jenkins
    final Enclosures enclosures

    public CheckCreator(Object jenkinsScript, Enclosures enclosures) {
        this.jenkins = jenkinsScript
        this.enclosures = enclosures
    }

    Closure javaChecks(Platform platform, Closure testStep, Closure analysisStep) {
        def mainClosure = basicCheckStructure(platform, {
            testStep.call(platform)
        },
                                            { analysisStep(platform) })
        def catchClosure = {throw it}
        def finallyClosure = {
            jenkins.junit allowEmptyResults: true, testResults: "**/build/test-results/**/*.xml"
            jenkins.cleanWs()
        }

        def checkStep = platform.runsOnDocker?
                    enclosures.withDocker(platform, mainClosure, catchClosure, finallyClosure):
                    enclosures.simple(platform, mainClosure, catchClosure, finallyClosure)
        return checkStep
    }

    Closure csWDKChecks(UnityVersionPlatform versionBuild, Closure testStep, Closure analysisStep) {
        def mainClosure = basicCheckStructure(versionBuild.platform,
                            {testStep(versionBuild)}, {analysisStep(versionBuild)})
        def catchClosure = { Throwable e ->
            if (versionBuild.optional) {
                jenkins.unstable(message: "Unity build for optional version ${versionBuild.version} is found to be unstable\n${e.toString()}")
            }
            else {
                throw e
            }
        }
        def finallyClosure = {
            jenkins.nunit failIfNoResults: false, testResultsPattern: '**/build/reports/unity/test*/*.xml'
            jenkins.archiveArtifacts artifacts: '**/build/logs/**/*.log', allowEmptyArchive: true
            jenkins.archiveArtifacts artifacts: '**/build/reports/unity/**/*.xml' , allowEmptyArchive: true
            jenkins.cleanWs()
        }
        def checkStep = enclosures.simple(versionBuild.platform, mainClosure, catchClosure, finallyClosure)
        return checkStep
    }

    protected Closure basicCheckStructure(Platform platform, Closure testStep, Closure analysisStep) {
        return {
            testStep()
            if (platform.isMain()) {
                analysisStep()
            }
        }
    }

}


