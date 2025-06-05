package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.check.steps.GradleSteps
import net.wooga.jenkins.pipeline.model.Docker
import net.wooga.jenkins.pipeline.model.Gradle

class Checks {

    GradleSteps steps
    CheckCreator checkCreator

    //jenkins CPS-transformations doesn't work inside constructors, so we have to keep these as simple as possible.
    //for non-trivial constructors, prefer static factories.
    static Checks create(Object jenkinsScript, Docker docker, Gradle gradle, int buildNumber) {
        def enclosureCreator = new EnclosureCreator(jenkinsScript, buildNumber)
        def enclosures = new Enclosures(jenkinsScript, docker, enclosureCreator)
        def checkCreator = new CheckCreator(jenkinsScript, enclosures)
        def steps = new GradleSteps(jenkinsScript, gradle)

        return new Checks(steps, checkCreator)
    }

    private Checks(GradleSteps steps, CheckCreator checkCreator) {
        this.steps = steps
        this.checkCreator = checkCreator
    }

    JavaChecks forJavaPipelines() {
        return new JavaChecks(checkCreator, steps)
    }

    WDKChecks forWDKPipelines() {
        return new WDKChecks(checkCreator, steps)
    }

    JSChecks forJSPipelines() {
        return new JSChecks(checkCreator, steps)
    }
}
