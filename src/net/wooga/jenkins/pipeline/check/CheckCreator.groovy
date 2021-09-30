package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.config.Platform
import net.wooga.jenkins.pipeline.config.UnityVersionPlatform

class CheckCreator {
    final Object jenkins
    final Enclosures enclosures
    protected boolean shouldRunAnalysis

    public CheckCreator(Object jenkinsScript, Enclosures enclosures) {
        this.jenkins = jenkinsScript
        this.enclosures = enclosures
        this.shouldRunAnalysis = true
    }

    Map<String, Closure> javaChecks(Platform[] platforms, Closure testStep, Closure analysisStep) {
        return platforms.collectEntries { platform ->
            def mainClosure = basicCheckStructure({ testStep(platform) },
                                                { analysisStep(platform) })
            def catchClosure = {throw it}
            def finallyClosure = {
                jenkins.junit allowEmptyResults: true, testResults: "**/build/test-results/**/*.xml"
                jenkins.cleanWs()
            }

            def checkStep = platform.runsOnDocker?
                        enclosures.withDocker(platform, mainClosure, catchClosure, finallyClosure):
                        enclosures.simple(platform, mainClosure, catchClosure, finallyClosure)
            return [("check ${platform.name}".toString()): checkStep]
        }
    }

    Map<String, Closure> csWDKChecks(UnityVersionPlatform[] versions, Closure testStep, Closure analysisStep) {
        return versions.collectEntries { versionBuild ->
            def mainClosure = basicCheckStructure({ testStep(versionBuild) },
                                               { analysisStep(versionBuild) })
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
            return [("check ${versionBuild.stepLabel}".toString()): checkStep]
        }
    }

    protected Closure basicCheckStructure(Closure testStep, Closure analysisStep) {
        return {
            testStep()
            boolean runsAnalysis = false
            jenkins.lock { runsAnalysis = getAndLockAnalysis() }
            if (runsAnalysis) {
                analysisStep()
            }
        }
    }

    protected boolean getAndLockAnalysis() {
        def val = shouldRunAnalysis
        shouldRunAnalysis = false
        return val
    }

}


