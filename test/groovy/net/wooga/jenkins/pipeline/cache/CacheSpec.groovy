package net.wooga.jenkins.pipeline.cache

import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Paths

class FakeJenkins {
    def BRANCH_NAME = "branch"
    def env = [BRANCH_NAME: BRANCH_NAME]
    List<String> failMatchingSh = []
    final List<Map<String, Object>> shInvocations = []
    def failShStatus = 1

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
        shInvocations.add(args)
        if (failMatchingSh.any { args.script.toString().matches(it) }) {
            return fakeSh(args, failShStatus)
        } else {
            return realSh(args)
        }
    }

    protected def realSh(Map<String, Object> args) {
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

    protected def fakeSh(Map<String, Object> args, int fakeStatus) {
        def returnStatus = args.getOrDefault("returnStatus", false)
        if (returnStatus) {
            return fakeStatus // Simulate success
        } else if(fakeStatus != 0) {
            throw new RuntimeException("Script failed with status $fakeStatus: $args.script")
        }
    }
}

class CacheSpec extends Specification {

    def "renews project cache with valid parameters"() {
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
        def success = cache.renewProjectCache("testFolder", 1000) {
            jenkins.echo("Generating assets")
            Thread.sleep(500)
        }

        then:
        success
        cache.lastModified.ageMs > 0 && cache.lastModified.ageMs < 1000 // Ensure the cache was updated
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

    def "deletes cache content if cache renewal failed"() {
        given: "a Cache instance with mock Jenkins"
        def cacheRoot = Files.createTempDirectory("cacheRoot").toFile()
        def jenkins = new FakeJenkins()
        jenkins.failMatchingSh = [
                "^.*gtar\\s.+\$",
                "^.*rsync\\s.+\$"
        ]
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
        def success = cache.renewProjectCache("testFolder", 1000) {
            jenkins.echo("Generating assets")
        }

        then:
        !success
        jenkins.shInvocations.size() > 0
        jenkins.shInvocations.any {
            it.script.toString().contains("rm -rf '${cacheRoot.absolutePath}/project/branch/testFolder'")
        }
        cleanup:
        jenkins.workspaceRoot.deleteDir()
        cacheRoot.deleteDir()
    }

    def "deletes local content and retries if cache fetch fails"() {
        given: "a Cache instance with mock Jenkins"
        def cacheRoot = Files.createTempDirectory("cacheRoot").toFile()
        def projectCache = new File(cacheRoot, "project/branch")
        def jenkins = new FakeJenkins()
        jenkins.failMatchingSh = [
                "^.*gtar\\s.+\$",
                "^.*rsync\\s.+\$"
        ]
        projectCache.with {
            new File(projectCache, "testFolder/Subfolder/file1.txt").with {
                it.parentFile.mkdirs()
                it.createNewFile()
            }
            new File(projectCache, "testFolder/file2.asset").createNewFile()
            new File(projectCache, "testFolder/file3.asset").createNewFile()
            new File(projectCache, ".last_renew").text = System.currentTimeMillis().toString()
        }
        jenkins.workspaceRoot.with {
            new File(it, "testFolder/Subfolder/file1.txt").with {
                it.parentFile.mkdirs()
                it.createNewFile()
            }
        }
        def cache = new Cache(jenkins, cacheRoot.absolutePath, "project", true)

        when: "renewProjectCache is called with a closure that generates assets"
        def success = cache.loadFromCache("testFolder")

        then:
        !success
        jenkins.shInvocations.size() > 0
        jenkins.shInvocations.collect{it.script.toString()}.with { _ ->
            assert count {it.matches("^.*gtar\\s.+'${cacheRoot.absolutePath}/project/branch' -c 'testFolder/'.*\$") } == 1
            assert count {it.matches("^.*rsync\\s.+'${cacheRoot.absolutePath}/project/branch/testFolder/' 'testFolder'.*\$") } == 1
        }
        !new File(jenkins.workspaceRoot,'project/branch/testFolder').exists()
        cleanup:
        jenkins.workspaceRoot.deleteDir()
        cacheRoot.deleteDir()
    }
}
