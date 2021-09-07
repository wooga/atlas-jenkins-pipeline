package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.config.Config

class Checks {

    static Checks forConfig(Object jenkinsScript, Config config) {
        def docker = new Docker(jenkinsScript)
        def commands = Commands.fromJenkins(jenkinsScript)
        def enclosureCreator = new EnclosureCreator(jenkinsScript, config.metadata.buildNumber)
        def enclosures = new Enclosures(config, docker, enclosureCreator)
        def checkCreator = new CheckCreator(jenkinsScript, config, enclosures)
        return new Checks(jenkinsScript, config, checkCreator, commands)
    }

    final Object jenkins
    final Config config
    final CheckCreator checkCreator

    final Gradle gradle
    final Sonarqube sonarqube
    final Coveralls coveralls

    Checks(Object jenkinsScript, Config config, CheckCreator checkCreator, Commands commands) {
        this.jenkins = jenkinsScript
        this.config = config
        this.checkCreator = checkCreator
        this.gradle = commands.gradle
        this.sonarqube = commands.sonarqube
        this.coveralls = commands.coveralls
    }

    Map<String, Closure> withCoverage(boolean forceSonarQube) {
        return checkCreator.createChecks({
            jenkins.checkout(jenkins.scm)
            gradle.wrapper("check")
        }, {
            gradle.wrapper("jacocoTestReport")
            sonarqube.runGradle(gradle, config.sonarArgs, config.metadata.branchName, forceSonarQube)
            coveralls.runGradle(gradle, config.coverallsToken)
        })
    }

    Map<String, Closure> simple(Closure testStep, Closure analysisStep) {
        return checkCreator.createChecks(testStep, analysisStep)
    }

}
