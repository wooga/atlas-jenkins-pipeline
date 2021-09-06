package net.wooga.jenkins.pipeline.config

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

    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        DockerArgs that = (DockerArgs) o

        if (!Arrays.equals(buildArgs, that.buildArgs)) return false
        if (fileDirectory != that.fileDirectory) return false
        if (fileName != that.fileName) return false
        if (image != that.image) return false
        if (!Arrays.equals(imageArgs, that.imageArgs)) return false

        return true
    }

    int hashCode() {
        int result
        result = (image != null ? image.hashCode() : 0)
        result = 31 * result + (fileName != null ? fileName.hashCode() : 0)
        result = 31 * result + (fileDirectory != null ? fileDirectory.hashCode() : 0)
        result = 31 * result + (buildArgs != null ? Arrays.hashCode(buildArgs) : 0)
        result = 31 * result + (imageArgs != null ? Arrays.hashCode(imageArgs) : 0)
        return result
    }
}


