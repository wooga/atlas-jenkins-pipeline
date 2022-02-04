package net.wooga.jenkins.pipeline.check


import net.wooga.jenkins.pipeline.config.PipelineConfig
import net.wooga.jenkins.pipeline.model.Docker
import net.wooga.jenkins.pipeline.model.Gradle

class Checks {

    Docker docker
    Gradle gradle
    EnclosureCreator enclosureCreator
    Enclosures enclosures
    CheckCreator checkCreator

    //jenkins CPS-transformations doesn't work inside constructors, so we have to keep these as simple as possible.
    //for non-trivial constructors, prefer static factories.
    private static Checks create(Object jenkinsScript, PipelineConfig config) {
        def docker = new Docker(jenkinsScript)
        def gradle = Gradle.fromJenkins(jenkinsScript, config.gradleArgs)
        def enclosureCreator = new EnclosureCreator(jenkinsScript, config.metadata.buildNumber)
        def enclosures = new Enclosures(docker, config.dockerArgs, enclosureCreator)
        def checkCreator = new CheckCreator(jenkinsScript, enclosures)

        return new Checks(docker, gradle, enclosureCreator, enclosures, checkCreator)
    }

    private Checks(Docker docker, Gradle gradle, EnclosureCreator enclosureCreator,
           Enclosures enclosures, CheckCreator checkCreator) {
        this.docker = docker
        this.gradle = gradle
        this.enclosureCreator = enclosureCreator
        this.enclosures = enclosures
        this.checkCreator = checkCreator
    }

    static JavaChecks forJavaPipelines(Object jenkinsScript, PipelineConfig config) {
        return create(jenkinsScript, config).forJavaPipelines(jenkinsScript)
    }

    static WDKChecks forWDKPipelines(Object jenkinsScript, PipelineConfig config) {
        return create(jenkinsScript, config).forWDKPipelines(jenkinsScript)
    }

    JavaChecks forJavaPipelines(Object jenkinsScript) {
        return new JavaChecks(jenkinsScript, checkCreator, gradle)
    }

    WDKChecks forWDKPipelines(Object jenkinsScript) {
        return new WDKChecks(jenkinsScript, checkCreator, gradle)
    }
}
