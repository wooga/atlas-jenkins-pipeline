package net.wooga.jenkins.pipeline.config

import spock.lang.Specification
import spock.lang.Unroll

class DockerArgsSpec extends Specification {

    @Unroll
    def "creates docker args from config map"() {
        given: "a docker args configuration map field"
        def dockerArgs = [image: image, dockerFileName: filename, dockerFileDirectory: fileDir, dockerBuildArgs: buildArgs, dockerArgs: imgArgs]

        when: "generating config object from map"
        def config = DockerArgs.fromConfigMap(dockerArgs)

        then: "generated config object is valid and matches map values"
        config == expected

        where:
        image   | filename | fileDir | buildArgs | imgArgs  | expected
        null    | null     | null    | null      | null     | new DockerArgs(null, "Dockerfile", ".", [], [])
        "image" | null     | null    | null      | null     | new DockerArgs("image", "Dockerfile", ".", [], [])
        null    | "file"   | null    | null      | null     | new DockerArgs(null, "file", ".", [], [])
        null    | null     | "dir"   | null      | null     | new DockerArgs(null, "Dockerfile", "dir", [], [])
        null    | null     | null    | ["arg"]   | null     | new DockerArgs(null, "Dockerfile", ".", ["arg"], [])
        null    | null     | null    | null      | ["arg"]  | new DockerArgs(null, "Dockerfile", ".", [], ["arg"])
        "image" | "file"   | "dir"   | ["barg"]  | ["darg"] | new DockerArgs("image", "file", "dir", ["barg"], ["darg"])
    }
}
