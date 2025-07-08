package net.wooga.jenkins.pipeline.cache

import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Paths

class FakeJenkins {
    def BRANCH_NAME = "branch"
    def env = [BRANCH_NAME: BRANCH_NAME]
    def workspaceRoot = new File("build/tests/workspace").with {
        it.deleteDir()
        it.mkdirs()
        return it
    }

    def fileExists(String path) {
        def pathObj = Paths.get(path)
        if(pathObj.isAbsolute()) {
            return Files.exists(pathObj)
        } else {
            def file = new File(workspaceRoot, path)
            return file.exists()
        }
    }

    def readFile(String path) {
        def pathObj = Paths.get(path)
        if(!Files.exists(pathObj)) {
            throw new FileNotFoundException("File not found: $path")
        }
        if(pathObj.isAbsolute()) {
            return new String(Files.readAllBytes(pathObj))
        } else {
            def file = new File(workspaceRoot, path)
            return file.text
        }
    }

    def echo(String message) {
        println message
    }

    def sh(String script) {
        return sh([script: script, returnStatus: false])
    }

    def sh(Map<String, Object> args) {
        String script = args.script
        def returnStatus = args.getOrDefault("returnStatus", false)
        println "Executing script: $script"
        def stdout = new ByteArrayOutputStream()
        def stderr = new ByteArrayOutputStream()
        def process = new ProcessBuilder("bash", "-c", script)
                .directory(this.workspaceRoot)
                .start()
        process.waitForProcessOutput(stdout, stderr)
        print new String(stdout.toByteArray()) + new String(stderr.toByteArray())
        def status = process.exitValue()
        if (returnStatus) {
            return status // Simulate success
        } else if(status != 0) {
            throw new RuntimeException("Script failed with status $status: $script")
        }
    }
}

class CacheSpec extends Specification {

    def "renewProjectCache with valid parameters"() {
        given: "a Cache instance with mock Jenkins"
        def cacheRoot = Files.createTempDirectory("cacheRoot").toFile()
        def jenkins = new FakeJenkins()
        jenkins.workspaceRoot.with {
            new File(it, "testFolder/Subfolder/file1.txt").with {
                it.parentFile.mkdirs()
                it.createNewFile()
            }
            new File(it, "testFolder/file2.asset").createNewFile()
            new File(it, "testFolder/file3.asset").createNewFile()
        }
        def cache = new Cache(jenkins, cacheRoot.absolutePath, "project", true)

        when: "renewProjectCache is called with a closure that generates assets"
        cache.renewProjectCache("testFolder", 1000) {
            jenkins.echo("Generating assets")
            Thread.sleep(500)
        }

        then:
        cache.lastModified.ageMs < 1000 // Ensure the cache was updated
        assert Files.walk(cacheRoot.toPath()).anyMatch {
            it.toString().endsWith("/project/$jenkins.BRANCH_NAME/testFolder/Subfolder/file1.txt")
        }
        assert Files.walk(cacheRoot.toPath()).anyMatch {
            it.toString().endsWith("/project/$jenkins.BRANCH_NAME/testFolder/file2.asset")
        }
        assert Files.walk(cacheRoot.toPath()).anyMatch {
            it.toString().endsWith("/project/$jenkins.BRANCH_NAME/testFolder/file3.asset")
        }
        cleanup:
        jenkins.workspaceRoot.deleteDir()
        cacheRoot.deleteDir()
    }
}
