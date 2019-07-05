package net.wooga.jenkins.pipeline

import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

/**
* Creates a step closure from a unity version string.
**/
def transformIntoCheckStep(platform, testEnvironment, coverallsToken, config, checkClosure, finallyClosure) {
  return {
    def node_label = "${platform} && atlas"

    if(config.labels) {
      node_label += "&& ${config.labels}"
    }

    if(platform == "linux") {
      node_label = "linux && docker"
    }

    def dockerArgs = config.dockerArgs

    node(node_label) {
      try {
        testEnvironment = testEnvironment.collect { item ->
          if(item instanceof groovy.lang.Closure) {
            return item.call().toString()
          }

          return item.toString()
        }

        checkout scm
        withEnv(["TRAVIS_JOB_NUMBER=${BUILD_NUMBER}.${platform.toUpperCase()}"]) {
          withEnv(testEnvironment) {
            if(platform == "linux") {
              def image = null
              if(dockerArgs.dockerImage) {
                echo "Use docker image ${dockerArgs.dockerImage}"
                image = docker.image(dockerArgs.dockerImage)
              } else {
                def dockerFilePath = "${dockerArgs.dockerFileDirectory}/${dockerArgs.dockerFileName}"
                echo "Dockerfile Path: ${dockerFilePath}"

                if(!fileExists(dockerFilePath)) {
                  checkClosure.call()
                  return
                }

                def dockerfileContent = readFile(dockerFilePath)
                def buildArgs = dockerArgs.dockerBuildArgs.join(' ')
                def hash = Utils.stringToSHA1(dockerfileContent + "/n" + buildArgs)
                image = docker.build(hash, "-f ${dockerArgs.dockerFileName} " + buildArgs + " ${dockerArgs.dockerFileDirectory}")
              }

              def args = dockerArgs.dockerArgs.join(' ')
              image.inside(args) {
                checkClosure.call()
              }
            } else {
              checkClosure.call()
            }
          }
        }
      }
      catch(Exception error) {
        echo error.message
        currentBuild.result = 'FAILURE'
      }
      finally {
        finallyClosure.call()
      }
    }
  }
}

return this
