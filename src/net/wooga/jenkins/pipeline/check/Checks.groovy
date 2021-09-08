package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.config.Config
import net.wooga.jenkins.pipeline.config.DockerArgs
import net.wooga.jenkins.pipeline.config.UnityVersionPlatform
import net.wooga.jenkins.pipeline.config.WDKConfig
import net.wooga.jenkins.pipeline.model.Docker
import net.wooga.jenkins.pipeline.model.Gradle

class Checks {

    static Checks create(Object jenkinsScript, DockerArgs dockerArgs, int buildNumber) {
        def docker = new Docker(jenkinsScript)
        def commands = Commands.fromJenkins(jenkinsScript)
        def enclosureCreator = new EnclosureCreator(jenkinsScript, buildNumber)
        def enclosures = new Enclosures(docker, dockerArgs, enclosureCreator)
        def checkCreator = new CheckCreator(jenkinsScript, enclosures)
        def gradle = Gradle.fromJenkins(jenkinsScript)
        return new Checks(jenkinsScript, checkCreator, gradle, commands)
    }

    final Object jenkins
    final CheckCreator checkCreator

    final Gradle gradle
    final Sonarqube sonarqube
    final Coveralls coveralls

    Checks(Object jenkinsScript, CheckCreator checkCreator, Gradle gradle, Commands commands) {
        this.jenkins = jenkinsScript
        this.checkCreator = checkCreator
        this.gradle = gradle
        this.sonarqube = commands.sonarqube
        this.coveralls = commands.coveralls
    }

    Map<String, Closure> javaCoverage(Config config, boolean forceSonarQube) {
        return checkCreator.javaChecks(config.platforms, {
                jenkins.checkout(jenkins.scm)
                gradle.wrapper("check")
            }, {
                gradle.wrapper("jacocoTestReport")
                sonarqube.runGradle(gradle, config.sonarArgs, config.metadata.branchName, forceSonarQube)
                coveralls.runGradle(gradle, config.coverallsToken)
            })
    }

    Map<String, Closure> simple(Config config, Closure testStep, Closure analysisStep) {
        return checkCreator.javaChecks(config.platforms, testStep, analysisStep)
    }

    Map<String, Closure> simple(WDKConfig config, Closure testStep, Closure analysisStep) {
        return checkCreator.csWDKChecks(config.unityVersions, testStep, analysisStep)
    }

    Map<String, Closure> wdkCoverage(WDKConfig config, String releaseType, String releaseScope, String setupStashId="setup_w") {
        return checkCreator.csWDKChecks(config.unityVersions, { UnityVersionPlatform versionPlat ->
            jenkins.dir(versionPlat.directoryName) {
                jenkins.checkout(jenkins.scm)
                jenkins.unstash setupStashId
                gradle.wrapper("-Prelease.stage=${releaseType.trim()} " +
                        "-Prelease.scope=${releaseScope.trim()} " +
                        "check")
            }
        },{
            def coberturaAdapter = jenkins.istanbulCoberturaAdapter('**/codeCoverage/Cobertura.xml')
            jenkins.publishCoverage adapters: [coberturaAdapter],
                                    sourceFileResolver: jenkins.sourceFiles('STORE_LAST_BUILD')
        })
    }

}
