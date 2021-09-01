package net.wooga.jenkins.pipeline.config

import com.cloudbees.groovy.cps.NonCPS
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils


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

    Object createImage(Object docker) {
        if (this.image) {
            return docker.image(this.image)
        } else {
            def dockerFile = new File("${this.fileDirectory}/${this.fileName}") //not allowed in jenkins space
            if (dockerFile.exists()) {
                def dockerfileContent = dockerFile.text
                def buildArgs = this.buildArgs.join(' ')
                def hash = Utils.stringToSHA1(dockerfileContent + "/n" + buildArgs) //needs jenkins space
                return docker.build(hash, "-f ${this.fileName} " + buildArgs + " ${this.fileDirectory}")
            }
        }
        return null;
    }

    @NonCPS
    static String generateSha1(String content) {
        return (content)

    }
}
