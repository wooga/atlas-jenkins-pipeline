package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.check.steps.Step
import net.wooga.jenkins.pipeline.config.DockerArgs
import net.wooga.jenkins.pipeline.config.Platform
import net.wooga.jenkins.pipeline.config. WdkUnityBuildVersion
import net.wooga.jenkins.pipeline.model.Docker

class CheckCreator {
    final Object jenkins
    final Enclosures enclosures

    static CheckCreator basicCheckCreator(Object jenkins,
                                          int buildNumber,
                                          DockerArgs dockerArgs = DockerArgs.fromConfigMap([:])) {
        def docker = Docker.fromJenkins(jenkins, dockerArgs)
        def enclosureCreator = new EnclosureCreator(jenkins, buildNumber)
        def enclosures = new Enclosures(jenkins, docker, enclosureCreator)
        return new CheckCreator(jenkins, enclosures)
    }

    public CheckCreator(Object jenkinsScript, Enclosures enclosures) {
        this.jenkins = jenkinsScript
        this.enclosures = enclosures
    }

    Closure junitCheck(Platform platform, Step testStep, Step analysisStep) {
        def mainClosure = createCheck(testStep, analysisStep).pack(platform)
        def catchClosure = {throw it}
        def finallyClosure = {
            jenkins.junit allowEmptyResults: true, testResults: "**/build/test-results/**/*.xml"
        }

        def checkStep = platform.runsOnDocker ?
                enclosures.withDocker(platform, mainClosure, catchClosure, finallyClosure) :
                enclosures.simple(platform, mainClosure, catchClosure, finallyClosure)
        return checkStep
    }

    Closure csWDKChecks(WdkUnityBuildVersion versionBuild, Step testStep, Step analysisStep) {
        def mainClosure = createCheck(testStep, analysisStep).pack(versionBuild.platform)
        def catchClosure = { Throwable e ->
            if (versionBuild.optional) {
                jenkins.unstable(message: "Unity build for optional version ${versionBuild.version} is found to be unstable\n${e.toString()}")
            } else {
                throw e
            }
        }
        def finallyClosure = {
            jenkins.nunit failIfNoResults: false, testResultsPattern: '**/build/reports/unity/test*/*.xml'
            jenkins.archiveArtifacts artifacts: '**/build/logs/**/*.log', allowEmptyArchive: true
            jenkins.archiveArtifacts artifacts: '**/build/reports/unity/**/*.xml', allowEmptyArchive: true
        }
        def checkStep = enclosures.simple(versionBuild.platform, mainClosure, catchClosure, finallyClosure)
        return checkStep
    }

    Closure simpleCheck(Platform platform, Step mainStep, Closure catchCls, Closure finallyCls) {
        def packedMain = createCheck(mainStep, new Step({p -> })).pack(platform)
        enclosures.simple(platform, packedMain, catchCls, finallyCls)
    }

    Map<String, Closure> simpleParallel(String prefix, List<Platform> platforms, Closure checkStep, Closure catchCls, Closure finallyCls) {
        return platforms.collectEntries { platform ->
            String parallelStepName = "${prefix}${platform.name}".toString()
            return [(parallelStepName): this.simpleCheck(platform, new Step(checkStep), catchCls, finallyCls)]
        }
    }

    protected Step createCheck(Step testStep, Step analysisStep) {
        return new Step({ Platform platform ->
            jenkins.dir(platform.checkoutDirectory) {
                jenkins.dir(platform.checkDirectory) {
                    testStep(platform)
                    if (platform.isMain()) {
                        analysisStep(platform)
                    }
                }
            }
        })
    }
}


