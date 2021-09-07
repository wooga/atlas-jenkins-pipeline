package net.wooga.jenkins.pipeline.model

import net.wooga.jenkins.pipeline.config.DockerArgs

class Docker {
    private final Object jenkins

    Docker(Object jenkins) {
        this.jenkins = jenkins
    }

    void runOnImage(DockerArgs dockerArgs, Closure mainClosure) {
        def image = createImage(dockerArgs)
        if(image != null) {
            image.inside(dockerArgs.dockerImageArgs) { mainClosure.call() }
        } else {
            mainClosure.call()
        }
    }

    protected Object createImage(DockerArgs args) {
        def docker = jenkins.docker
        if (args.image) {
            return docker.image(args.image)
        } else {
            if (jenkins.fileExists(args.fullFilePath)) {
                String dockerfileContent = jenkins.readFile(args.fullFilePath)
                def hash = jenkins.utils().stringToSHA1(dockerfileContent + "/n" + args.dockerBuildString)
                return docker.build(hash, args.dockerBuildString)
            }
        }
        return null;
    }

}
