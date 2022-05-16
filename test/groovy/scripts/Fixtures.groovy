package scripts

import java.util.concurrent.atomic.AtomicBoolean

class Fixtures {

    static def createTmpFile(String dir = ".", String file) {
        if (file != null) {
            new File(dir).mkdirs()
            new File(dir, file).with {
                createNewFile()
                deleteOnExit()
            }
        }
    }

    static def createDockerFake(String dockerfile, String image, String dockerDir,
                                List<String> dockerBuildArgs, List<String> dockerArgs) {
        AtomicBoolean ran = new AtomicBoolean(false)
        def imgMock = [inside: { args, cls ->
            if (args == dockerArgs.join(" ")) {
                ran.set(true)
                cls()
            }
        }]

        def buildArgs = ["-f ${dockerfile}".toString()]
        if(dockerBuildArgs?.size() > 0) {
            buildArgs.add(dockerBuildArgs.join(" "))
        }
        buildArgs.add(dockerDir)
        return [image: { name -> name == image ? imgMock : null },
                build: { hash, args -> args == buildArgs.join(" ") ? imgMock : null },
                ran  : ran]
    }
}
