package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.check.steps.Step
import net.wooga.jenkins.pipeline.config.Platform
import net.wooga.jenkins.pipeline.model.Docker

class Checks {
    final Object jenkins
    final Enclosures enclosures

    static Checks create(Object jenkinsScript, Docker docker, int buildNumber) {
        def enclosureCreator = new EnclosureCreator(jenkinsScript, buildNumber)
        def enclosures = new Enclosures(jenkinsScript, docker, enclosureCreator)
        return new Checks(jenkinsScript, enclosures)
    }

    public Checks(Object jenkinsScript, Enclosures enclosures) {
        this.jenkins = jenkinsScript
        this.enclosures = enclosures
    }

    Step enclosedSimpleCheck(Step testStep, Step analysisStep, Closure catchClosure, Closure finallyClosure) {
        return simpleCheck(testStep, analysisStep).wrappedBy { checkStep, platform ->
            def enclosedPackedStep = platform.runsOnDocker ?
                    enclosures.withDocker(platform, checkStep.pack(platform), catchClosure, finallyClosure) :
                    enclosures.simple(platform, checkStep.pack(platform), catchClosure, finallyClosure)
            enclosedPackedStep()
        }
    }

    Step simpleCheck(Step testStep, Step analysisStep) {
        return new Step({ Platform platform ->
            jenkins.dir(platform.checkDirectory) {
                testStep(platform)
                if (platform.isMain()) {
                    analysisStep(platform)
                }
            }
        })
    }


}


