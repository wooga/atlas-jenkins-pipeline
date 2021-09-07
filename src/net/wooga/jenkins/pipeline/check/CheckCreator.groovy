package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.config.Config

class CheckCreator {
    final Object jenkins
    final Config config
    final Enclosures enclosures
    protected boolean shouldRunAnalysis

    public CheckCreator(Object jenkinsScript, Config config, Enclosures enclosures) {
        this.jenkins = jenkinsScript
        this.config = config
        this.enclosures = enclosures
        this.shouldRunAnalysis = true
    }

    Map<String, Closure> createChecks(Closure testStep, Closure analysisStep) {
        return config.platforms.collectEntries { platform ->
            def mainClosure = basicCheckStructure(testStep, analysisStep)
            def catchClosure = {throw it}
            def finallyClosure = {jenkins.cleanWs()}

            def checkStep = platform.runsOnDocker?
                        enclosures.withDocker(platform, mainClosure, catchClosure, finallyClosure):
                        enclosures.simple(platform, mainClosure, catchClosure, finallyClosure)
            return [("check ${platform.name}".toString()): checkStep]
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
            jenkins.junit allowEmptyResults: true, testResults: "**/build/test-results/**/*.xml"
        }
    }

    protected boolean getAndLockAnalysis() {
        def val = shouldRunAnalysis
        shouldRunAnalysis = false
        return val
    }

}


