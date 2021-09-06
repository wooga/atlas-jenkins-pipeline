package net.wooga.jenkins.pipeline.config

import com.cloudbees.groovy.cps.NonCPS
import groovy.transform.EqualsAndHashCode
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

@EqualsAndHashCode
class DockerArgs {

    final String image
    final String fileName
    final String fileDirectory
    final String[] buildArgs
    final String[] imageArgs

    static DockerArgs fromConfigMap(Map dockerArgs) {
        return new DockerArgs(
                dockerArgs.image as String,
                dockerArgs.dockerFileName as String ?: "Dockerfile",
                dockerArgs.dockerFileDirectory as String ?: ".",
                dockerArgs.dockerBuildArgs as List<String> ?: [],
                dockerArgs.dockerArgs as List<String>?: []
        )
    }

    DockerArgs(String image, String fileName, String fileDirectory,
               List<String> buildArgs, List<String> imageArgs) {
        this.image = image
        this.fileName = fileName
        this.fileDirectory = fileDirectory
        this.buildArgs = buildArgs
        this.imageArgs = imageArgs
    }

    String getFullFilePath() {
        return "${fileDirectory}/${fileName}"
    }

    String getDockerBuildString() {
        return "-f ${fileName} ${buildArgs} ${fileDirectory}".toString()
    }

    String getDockerImageArgs() {
        return imageArgs.join(" ")
    }
}


