package net.wooga.jenkins.pipeline

import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

/**
 * Creates a "check" step for use in a jenkins pipeline
 **/
def transformIntoCheckStep(String platform, Map testEnvironment, String coverallsToken, Map config, Closure checkClosure, Closure finallyClosure, Boolean skipCheckout = false) {

    return this.createCheckStep(args)
    this.createCheckStep([platform: platform,
            testEnvironment: testEnvironment,
            config: config,
            checkClosure: checkClosure,
            catchClosure: catchClosure,
            finallyClosure: finallyClosure,
            skipCheckout: skipCheckout])
}

/**
 * Creates a "check" step for use in a jenkins pipeline
 **/
def createCheckStep(Map args) {

    return {
        def node_label = "atlas"
        if (args.platform) {
            node_label += "&& ${args.platform}"
        }

        if (args.config.labels) {
            node_label += "&& ${args.config.labels}"
        }

        if (args.platform == "linux") {
            node_label = "linux && docker"
        }

        def dockerArgs = args.config.dockerArgs

        node(node_label) {
            try {
                testEnvironment = args.testEnvironment.collect { item ->
                    if (item instanceof groovy.lang.Closure) {
                        return item.call().toString()
                    }

                    return item.toString()
                }

                if (!args.skipCheckout) {
                    checkout scm
                }

                withEnv(["TRAVIS_JOB_NUMBER=${BUILD_NUMBER}.${args.platform.toUpperCase()}"]) {
                    withEnv(args.testEnvironment) {
                        if (args.platform == "linux") {
                            def image = null
                            if (dockerArgs.dockerImage) {
                                echo "Use docker image ${dockerArgs.dockerImage}"
                                image = docker.image(dockerArgs.dockerImage)
                            } else {
                                def dockerFilePath = "${dockerArgs.dockerFileDirectory}/${dockerArgs.dockerFileName}"
                                echo "Dockerfile Path: ${dockerFilePath}"

                                if (!fileExists(dockerFilePath)) {
                                    args.checkClosure.call()
                                    return
                                }

                                def dockerfileContent = readFile(dockerFilePath)
                                def buildArgs = dockerArgs.dockerBuildArgs.join(' ')
                                def hash = Utils.stringToSHA1(dockerfileContent + "/n" + buildArgs)
                                image = docker.build(hash, "-f ${dockerArgs.dockerFileName} " + buildArgs + " ${dockerArgs.dockerFileDirectory}")
                            }

                            def imageArgs = dockerArgs.dockerArgs.join(' ')
                            image.inside(imageArgs) {
                                args.checkClosure.call()
                            }
                        } else {
                            args.checkClosure.call()
                        }
                    }
                }
            }
            catch (Exception e) {
                if (args.catchClosure) {
                    args.catchClosure.call(e)
                } else {
                    throw e
                }
            }
            finally {
                args.finallyClosure.call()
            }
        }
    }
}

return this
