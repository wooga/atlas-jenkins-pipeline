

import net.wooga.jenkins.pipeline.config.DockerArgs

def call(DockerArgs dockerArgs) {
    return [
        wrap: { Closure mainClosure ->
                return wrapWithDocker(dockerArgs, mainClosure)
        }
    ]
}

void wrapWithDocker(DockerArgs dockerArgs, Closure mainClosure) {
    def image = createImage(dockerArgs)
    if(image != null) {
        image.inside(dockerArgs.dockerImageArgs) { mainClosure.call() }
    } else {
        mainClosure.call()
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