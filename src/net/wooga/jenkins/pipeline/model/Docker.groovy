package net.wooga.jenkins.pipeline.model

import net.wooga.jenkins.pipeline.check.steps.PackedStep
import net.wooga.jenkins.pipeline.config.DockerArgs

class Docker {
    private final Object jenkins
    private final DockerArgs dockerArgs

    static Docker fromJenkins(Object jenkins, DockerArgs dockerArgs) {
        return new Docker(jenkins, dockerArgs)
    }

    Docker(Object jenkins, DockerArgs dockerArgs) {
        this.jenkins = jenkins
        this.dockerArgs = dockerArgs
    }

    void runOnImage(PackedStep main) {
        def image = createImage(dockerArgs)
        if(image != null) {
            image.inside(dockerArgs.dockerImageArgs) {
                jenkins.echo("Running inside Dockerfile")
                main.call()
            }
        } else {
            main.call()
        }
    }

    protected Object createImage(DockerArgs args) {

        def docker = jenkins.docker
        if (args.image) {
            jenkins.echo("Using given image $args.image")
            return docker.image(args.image)
        } else {
            if (jenkins.fileExists(args.fullFilePath)) {
                String dockerfileContent = jenkins.readFile(args.fullFilePath)
                def hash = jenkins.utils().stringToSHA1(dockerfileContent + "/n" + args.dockerBuildString)
                return docker.build(hash, args.dockerBuildString)
            }
            jenkins.echo("Dockerfile $args.fullFilePath not found")
        }
        return null;
    }

}
