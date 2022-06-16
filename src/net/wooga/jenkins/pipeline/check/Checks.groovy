package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.check.steps.GradleSteps
import net.wooga.jenkins.pipeline.model.Docker
import net.wooga.jenkins.pipeline.model.Gradle

class Checks {

    Docker docker
    Gradle gradle
    GradleSteps steps
    NodeCreator nodeCreator
    Enclosures enclosures
    CheckCreator checkCreator

    //jenkins CPS-transformations doesn't work inside constructors, so we have to keep these as simple as possible.
    //for non-trivial constructors, prefer static factories.
    static Checks create(Object jenkinsScript, Docker docker, Gradle gradle, int buildNumber) {
        def enclosureCreator = new NodeCreator(jenkinsScript)
        def enclosures = new Enclosures(jenkinsScript, docker, enclosureCreator, buildNumber)
        def checkCreator = new CheckCreator(jenkinsScript, enclosures)
        def steps = new GradleSteps(jenkinsScript, gradle)

        return new Checks(docker, gradle, steps, enclosureCreator, enclosures, checkCreator)
    }

    private Checks(Docker docker, Gradle gradle, GradleSteps steps, NodeCreator enclosureCreator,
                   Enclosures enclosures, CheckCreator checkCreator) {
        this.docker = docker
        this.gradle = gradle
        this.steps = steps
        this.nodeCreator = enclosureCreator
        this.enclosures = enclosures
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
