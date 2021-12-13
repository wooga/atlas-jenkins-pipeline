package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.config.JavaConfig
import net.wooga.jenkins.pipeline.config.DockerArgs
import net.wooga.jenkins.pipeline.config.GradleArgs
import net.wooga.jenkins.pipeline.config.PipelineConfig
import net.wooga.jenkins.pipeline.model.Docker
import net.wooga.jenkins.pipeline.model.Gradle

class Checks {

    Docker docker
    Sonarqube sonarqube
    Coveralls coveralls
    Gradle gradle
    EnclosureCreator enclosureCreator
    Enclosures enclosures
    CheckCreator checkCreator

    private Checks(Object jenkinsScript, PipelineConfig config) {
        this(jenkinsScript, config.gradleArgs, config.dockerArgs, config.metadata.buildNumber)
    }

    private Checks(Object jenkinsScript, GradleArgs gradleArgs, DockerArgs dockerArgs, int buildNumber) {
        this.docker = new Docker(jenkinsScript)
        this.sonarqube = new Sonarqube()
        this.coveralls = new Coveralls(jenkinsScript)
        this.gradle = Gradle.fromJenkins(jenkinsScript, gradleArgs)
        this.enclosureCreator = new EnclosureCreator(jenkinsScript, buildNumber)
        this.enclosures = new Enclosures(docker, dockerArgs, enclosureCreator)
        this.checkCreator = new CheckCreator(jenkinsScript, enclosures)
    }

    static forJavaPipelines(Object jenkinsScript, PipelineConfig config) {
        return new Checks(jenkinsScript, config).forJavaPipelines(jenkinsScript)
    }

    static forWDKPipelines(Object jenkinsScript, PipelineConfig config) {
        return new Checks(jenkinsScript, config).forWDKPipelines(jenkinsScript)
    }

    JavaChecks forJavaPipelines(Object jenkinsScript) {
        return new JavaChecks(jenkinsScript, checkCreator, gradle, sonarqube, coveralls)
    }

    WDKChecks forWDKPipelines(Object jenkinsScript) {
        return new WDKChecks(jenkinsScript, checkCreator, gradle, sonarqube, coveralls)
    }
}
