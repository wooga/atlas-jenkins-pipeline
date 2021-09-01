package net.wooga.jenkins.pipeline.scripts

import net.wooga.jenkins.pipeline.config.DockerArgs
import net.wooga.jenkins.pipeline.config.Platform
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

def call(Platform platform, int buildNumber) {
    def testEnvironment = platform.testEnvironment +
            ["TRAVIS_JOB_NUMBER=${buildNumber}.${platform.name.toUpperCase()}"]
    return [
            withDocker: { DockerArgs dockerArgs -> withDocker(platform, testEnvironment, dockerArgs)}
    ]
}

def withDocker(Platform platform, Collection testEnvironment, DockerArgs dockerArgs) {
    return [
        wrap: { Closure mainClosure, Closure catchClosure={throw it}, Closure finallyClosure=null ->
            return wrapWithDocker(platform, testEnvironment, dockerArgs, mainClosure, catchClosure, finallyClosure)
        }
    ]
}

Closure wrapWithDocker(Platform platform, Collection testEnvironment, DockerArgs dockerArgs,
        Closure mainClosure, Closure catchClosure={throw it}, Closure finallyClosure=null) {
    def nodeLabel = "atlas && ${platform.testLabels}"
    return {
        node(nodeLabel) {
            try {
                withEnv(testEnvironment) {
                    if (platform.name == "linux") {
                        def image = createImage(dockerArgs)
                        if(image != null) {
                            image.inside(dockerArgs.dockerImageArgs) { mainClosure.call() }
                        } else {
                            mainClosure.call()
                        }
                    } else {
                        mainClosure.call()
                    }
                }
            }
            catch (Exception e) {
                catchClosure.call(e)
            }
            finally {
                finallyClosure?.call()
            }
        }
    }
}

def createImage(DockerArgs args) {
    if (args.image) {
        return docker.image(args.image)
    } else {
        if (fileExists(args.fullFilePath)) {
            String dockerfileContent = readFile(args.fullFilePath)
            def hash = utils().stringToSHA1(dockerfileContent + "/n" + args.dockerBuildString)
            return docker.build(hash, args.dockerBuildString)
        }
    }
    return null;
}