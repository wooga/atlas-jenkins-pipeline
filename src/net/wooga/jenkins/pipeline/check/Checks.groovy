package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.model.Docker
import net.wooga.jenkins.pipeline.model.Gradle

class Checks {

    Object jenkins
    Docker docker
    Gradle gradle
    GradleSteps steps
    EnclosureCreator enclosureCreator
    Enclosures enclosures
    CheckCreator checkCreator

    //jenkins CPS-transformations doesn't work inside constructors, so we have to keep these as simple as possible.
    //for non-trivial constructors, prefer static factories.
    static Checks create(Object jenkinsScript, Docker docker, Gradle gradle, int buildNumber) {
        def enclosureCreator = new EnclosureCreator(jenkinsScript, buildNumber)
        def enclosures = new Enclosures(docker, enclosureCreator)
        def checkCreator = new CheckCreator(jenkinsScript, enclosures)
        def steps = GradleSteps.fromJenkins(jenkinsScript, gradle)

        return new Checks(jenkinsScript, docker, gradle, steps, enclosureCreator, enclosures, checkCreator)
    }

    private Checks(Object jenkins, Docker docker, Gradle gradle, GradleSteps steps, EnclosureCreator enclosureCreator,
           Enclosures enclosures, CheckCreator checkCreator) {
        this.jenkins = jenkins
        this.docker = docker
        this.gradle = gradle
        this.steps = steps
        this.enclosureCreator = enclosureCreator
        this.enclosures = enclosures
        this.checkCreator = checkCreator
    }

    JavaChecks forJavaPipelines() {
        return new JavaChecks(jenkins, checkCreator, steps)
    }

    WDKChecks forWDKPipelines() {
        return new WDKChecks(jenkins, checkCreator, steps)
    }
}
