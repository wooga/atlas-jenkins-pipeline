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

    @Unroll
    def "generates docker build arguments string"() {
        given:
        when:
        def buildString = dockerArgs.dockerBuildString
        then:
        expectedBuildString == buildString

        where:
        dockerArgs                                                                     | expectedBuildString
        new DockerArgs(null, "Dockerfile", ".", [], [])                                | "-f Dockerfile ."
        new DockerArgs(null, "file", ".", [], [])                                      | "-f file ."
        new DockerArgs(null, "Dockerfile", "dir", [], [])                              | "-f Dockerfile dir"
        new DockerArgs(null, "Dockerfile", ".", ["-arg value"], [])                    | "-f Dockerfile -arg value ."
        new DockerArgs(null, "Dockerfile", ".", ["-arg value", "-arg othervalue"], []) | "-f Dockerfile -arg value -arg othervalue ."
        new DockerArgs(null, "Dockerfile", ".", [], ['arg'])                           | "-f Dockerfile ."
    }

    @Unroll
    def "generates docker image arguments string"() {
        given:
        when:
        def buildString = dockerArgs.dockerImageArgs
        then:
        expectedImageString == buildString

        where:
        dockerArgs                                                             | expectedImageString
        new DockerArgs(null, "Dockerfile", ".", [], [])                        | ""
        new DockerArgs("image", "file", ".", [], [])                           | ""
        new DockerArgs("image", "file", ".", [], ["-arg value"])               | "-arg value"
        new DockerArgs("image", "file", ".", [], ["-arg value", "othervalue"]) | "-arg value othervalue"
        new DockerArgs(null, "file", ".", [], ["-arg value", "othervalue"])    | "-arg value othervalue"
    }


    @Unroll
    def "generates dockerfile full path"() {
        given:
        when:
        def buildString = dockerArgs.fullFilePath
        then:
        expectedPath == buildString

        where:
        dockerArgs                                                 | expectedPath
        new DockerArgs(null, "Dockerfile", ".", [], [])            | "./Dockerfile"
        new DockerArgs("image", "file", ".", [], [])               | "./file"
        new DockerArgs("image", "file", "dir", [], ["-arg value"]) | "dir/file"
    }
}

