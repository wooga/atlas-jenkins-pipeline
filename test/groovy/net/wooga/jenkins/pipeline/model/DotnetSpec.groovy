package net.wooga.jenkins.pipeline.model

import spock.lang.Specification
import spock.lang.Unroll

class DotnetSpec extends Specification {

    static Expando fakeJenkins(boolean unix, Map<String, String> env = [:], boolean hasGlobalJson = false) {
        def jenkins = new Expando()
        jenkins.calls = [withEnv: [], sh: [], bat: [], powershell: [], writeFile: [], libraryResource: [], withCredentials: []]
        jenkins.isUnix = { -> unix }
        jenkins.env = env
        jenkins.fileExists = { String path -> path == 'global.json' && hasGlobalJson }
        jenkins.withEnv = { List envList, Closure body ->
            jenkins.calls.withEnv << envList
            body.call()
        }
        jenkins.sh = { Object arg -> jenkins.calls.sh << arg }
        jenkins.bat = { Object arg -> jenkins.calls.bat << arg }
        jenkins.powershell = { Object arg -> jenkins.calls.powershell << arg }
        jenkins.writeFile = { Map args -> jenkins.calls.writeFile << args }
        jenkins.libraryResource = { String path -> jenkins.calls.libraryResource << path; return "" }
        jenkins.usernamePassword = { Map args -> args }
        jenkins.withCredentials = { List bindings, Closure body ->
            jenkins.calls.withCredentials << bindings
            bindings.each { b ->
                if (b.usernameVariable) { env[b.usernameVariable] = "fake-jfrog-user" }
                if (b.passwordVariable) { env[b.passwordVariable] = "fake-jfrog-pass" }
            }
            body.call()
        }
        return jenkins
    }

    @Unroll
    def "rejects more than one selector (version=#version, channel=#channel, globalJson=#globalJson)"() {
        when:
        new Dotnet(fakeJenkins(true), version, channel, globalJson)

        then:
        thrown(IllegalArgumentException)

        where:
        version   | channel | globalJson
        "8.0.401" | "8.0"   | null
        "8.0.401" | null    | "global.json"
        null      | "8.0"   | "global.json"
        "8.0.401" | "8.0"   | "global.json"
    }

    @Unroll
    def "accepts at most one selector (version=#version, channel=#channel, globalJson=#globalJson)"() {
        when:
        new Dotnet(fakeJenkins(true), version, channel, globalJson)

        then:
        noExceptionThrown()

        where:
        version   | channel | globalJson
        null      | null    | null
        "8.0.401" | null    | null
        null      | "8.0"   | null
        null      | null    | "global.json"
    }

    @Unroll
    def "rejects invalid NuGet config (name=#nugetSourceName, url=#nugetSourceUrl, credentials=#nugetCredentialsId)"() {
        when:
        new Dotnet(fakeJenkins(true), null, null, null, nugetSourceName, nugetSourceUrl, nugetCredentialsId)

        then:
        thrown(IllegalArgumentException)

        where:
        nugetSourceName | nugetSourceUrl                    | nugetCredentialsId
        "my_source"     | null                               | null
        null            | "https://example.com/index.json"  | null
        null            | null                               | "my_creds"
        "my_source"     | null                               | "my_creds"
    }

    @Unroll
    def "accepts valid NuGet config combinations (name=#nugetSourceName, url=#nugetSourceUrl, credentials=#nugetCredentialsId)"() {
        when:
        new Dotnet(fakeJenkins(true), null, null, null, nugetSourceName, nugetSourceUrl, nugetCredentialsId)

        then:
        noExceptionThrown()

        where:
        nugetSourceName | nugetSourceUrl                   | nugetCredentialsId
        null            | null                              | null
        "my_source"     | "https://example.com/index.json" | null
        "my_source"     | "https://example.com/index.json" | "my_creds"
    }

    def "resolves unix cache dir from HOME"() {
        given:
        def dotnet = new Dotnet(fakeJenkins(true, [HOME: "/home/tester"]))

        expect:
        dotnet.cacheDir() == "/home/tester/.cache/jenkins-pipeline/dotnet"
    }

    def "resolves windows cache dir from LOCALAPPDATA"() {
        given:
        def dotnet = new Dotnet(fakeJenkins(false, [LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local"]))

        expect:
        dotnet.cacheDir() == "C:\\Users\\tester\\AppData\\Local\\cache\\jenkins-pipeline\\dotnet"
    }

    @Unroll
    def "install() passes the install dir and #description as CLI args, not env vars"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester"], hasGlobalJson)
        def dotnet = new Dotnet(jenkins, version, channel, globalJson)

        when:
        dotnet.install()

        then:
        jenkins.calls.withEnv.isEmpty()
        jenkins.calls.libraryResource == ["dotnet/dotnet-install.sh"]
        jenkins.calls.writeFile[0].file == ".ci/dotnet-install.sh"
        jenkins.calls.sh == ["chmod +x .ci/dotnet-install.sh && .ci/dotnet-install.sh --install-dir '/home/tester/.cache/jenkins-pipeline/dotnet'${expectedSuffix}".toString()]

        where:
        version   | channel | globalJson         | hasGlobalJson | description                               | expectedSuffix
        "8.0.401" | null    | null               | false         | "an explicit version"                     | " --version '8.0.401'"
        null      | "8.0"   | null               | false         | "an explicit channel"                     | " --channel '8.0'"
        null      | null    | "path/global.json" | false         | "an explicit globalJson"                  | " --global-json 'path/global.json'"
        null      | null    | null               | true          | "no extra flag (workspace global.json)"   | ""
        null      | null    | null               | false         | "the org-wide default version"            | " --default-version '${Dotnet.DEFAULT_VERSION}'"
    }

    def "install() uses the powershell wrapper on Windows with PascalCase flags"() {
        given:
        def jenkins = fakeJenkins(false, [LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.install()

        then:
        jenkins.calls.withEnv.isEmpty()
        jenkins.calls.libraryResource == ["dotnet/dotnet-install.ps1"]
        jenkins.calls.writeFile[0].file == ".ci/dotnet-install.ps1"
        jenkins.calls.powershell == [".ci\\dotnet-install.ps1 -InstallDir 'C:\\Users\\tester\\AppData\\Local\\cache\\jenkins-pipeline\\dotnet' -DefaultVersion '${Dotnet.DEFAULT_VERSION}'".toString()]
        jenkins.calls.sh.isEmpty()
    }

    def "install() quotes a CLI arg value containing a space (unix)"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester"])
        def dotnet = new Dotnet(jenkins, null, null, "/path with space/global.json")

        when:
        dotnet.install()

        then:
        jenkins.calls.sh[0].toString().contains("--global-json '/path with space/global.json'")
    }

    def "install() escapes an embedded single quote in a CLI arg value (unix)"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester"])
        def dotnet = new Dotnet(jenkins, null, null, "/path/o'brien/global.json")

        when:
        dotnet.install()

        then:
        jenkins.calls.sh[0].toString().contains("--global-json '/path/o'\"'\"'brien/global.json'")
    }

    def "install() doubles an embedded single quote in a CLI arg value (windows)"() {
        given:
        def jenkins = fakeJenkins(false, [LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local"])
        def dotnet = new Dotnet(jenkins, null, null, "C:\\path\\o'brien\\global.json")

        when:
        dotnet.install()

        then:
        jenkins.calls.powershell[0].toString().contains("-GlobalJson 'C:\\path\\o''brien\\global.json'")
    }

    def "withEnvList exposes the cache dir on PATH and as DOTNET_ROOT"() {
        given:
        def dotnet = new Dotnet(fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"]))

        expect:
        dotnet.withEnvList() == ["DOTNET_ROOT=/home/tester/.cache/jenkins-pipeline/dotnet", "PATH=/home/tester/.cache/jenkins-pipeline/dotnet:/usr/bin"]
    }

    def "withEnvList uses a semicolon PATH separator on Windows"() {
        given:
        def dotnet = new Dotnet(fakeJenkins(false, [LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local", PATH: "C:\\Windows"]))

        expect:
        dotnet.withEnvList() == ["DOTNET_ROOT=C:\\Users\\tester\\AppData\\Local\\cache\\jenkins-pipeline\\dotnet", "PATH=C:\\Users\\tester\\AppData\\Local\\cache\\jenkins-pipeline\\dotnet;C:\\Windows"]
    }

    def "withInstalledDotnet installs the SDK then runs the block; no NuGet config means NuGet is never touched"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins)
        def ran = false

        when:
        dotnet.withInstalledDotnet { ran = true }

        then:
        ran
        jenkins.calls.withCredentials.isEmpty()
        jenkins.calls.sh.size() == 1 // just the install wrapper, no nuget source call
        jenkins.calls.withEnv == [["DOTNET_ROOT=/home/tester/.cache/jenkins-pipeline/dotnet", "PATH=/home/tester/.cache/jenkins-pipeline/dotnet:/usr/bin"]]
    }

    def "withInstalledDotnet registers a NuGet source without binding credentials when only the source is given"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins, null, null, null, "my_source", "https://example.com/index.json", null)

        when:
        dotnet.withInstalledDotnet { }

        then:
        jenkins.calls.withCredentials.isEmpty()
        def nugetCall = jenkins.calls.sh.find { it.toString().contains("nuget add source") }
        nugetCall != null
        nugetCall.contains("https://example.com/index.json")
        nugetCall.contains("my_source")
        nugetCall.contains("dotnet nuget list source") // checks before adding, for idempotency
        jenkins.calls.withEnv.find { it.any { e -> e.toString().startsWith("NuGetPackageSourceCredentials_") } } == null
    }

    def "withInstalledDotnet registers the source and binds credentials when all three are given"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins, null, null, null, "my_source", "https://example.com/index.json", "my_creds")

        when:
        dotnet.withInstalledDotnet { }

        then:
        jenkins.calls.withCredentials == [[[credentialsId: "my_creds", usernameVariable: "JFROG_USER", passwordVariable: "JFROG_PASS"]]]
        def nugetCall = jenkins.calls.sh.find { it.toString().contains("nuget add source") }
        nugetCall != null
        nugetCall.contains("https://example.com/index.json")
        nugetCall.contains("my_source")
        jenkins.calls.withEnv[0].any { it.toString() == "NuGetPackageSourceCredentials_my_source=Username=fake-jfrog-user;Password=fake-jfrog-pass" }
    }

    def "withInstalledDotnet registers the NuGet source via powershell on Windows"() {
        given:
        def jenkins = fakeJenkins(false, [LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local", PATH: "C:\\Windows"])
        def dotnet = new Dotnet(jenkins, null, null, null, "my_source", "https://example.com/index.json", "my_creds")

        when:
        dotnet.withInstalledDotnet { }

        then:
        def nugetCall = jenkins.calls.powershell.find { it.toString().contains("nuget add source") }
        nugetCall != null
        nugetCall.contains("https://example.com/index.json")
        nugetCall.contains("my_source")
    }

    def "toolCacheDir resolves under cacheDir on unix"() {
        given:
        def dotnet = new Dotnet(fakeJenkins(true, [HOME: "/home/tester"]))

        expect:
        dotnet.toolCacheDir() == "/home/tester/.cache/jenkins-pipeline/dotnet/tools"
    }

    def "toolCacheDir resolves under cacheDir on Windows"() {
        given:
        def dotnet = new Dotnet(fakeJenkins(false, [LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local"]))

        expect:
        dotnet.toolCacheDir() == "C:\\Users\\tester\\AppData\\Local\\cache\\jenkins-pipeline\\dotnet\\tools"
    }

    def "withTool installs, installs the tool, then runs the block"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins)
        def ran = false

        when:
        dotnet.withTool("MyTool", null) { ran = true }

        then:
        ran
        def installCall = jenkins.calls.sh.find { it.toString().contains("dotnet tool install MyTool") }
        installCall != null
        installCall.contains("--create-manifest-if-needed")
        !installCall.contains("--version")
        !installCall.contains("--allow-downgrade")
    }

    def "withTool passes version and --allow-downgrade when a version is given"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.withTool("MyTool", "1.2.3") { }

        then:
        def installCall = jenkins.calls.sh.find { it.toString().contains("dotnet tool install MyTool") }
        installCall.contains("--version 1.2.3")
        installCall.contains("--allow-downgrade")
    }

    def "withTool redirects NUGET_PACKAGES and DOTNET_CLI_HOME under the tool cache dir"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.withTool("MyTool", null) { }

        then:
        def toolEnvCall = jenkins.calls.withEnv.find { it.any { e -> e.toString().startsWith("NUGET_PACKAGES=") } }
        toolEnvCall != null
        toolEnvCall.any { it.toString() == "NUGET_PACKAGES=/home/tester/.cache/jenkins-pipeline/dotnet/tools/packages" }
        toolEnvCall.any { it.toString() == "DOTNET_CLI_HOME=/home/tester/.cache/jenkins-pipeline/dotnet/tools" }
    }

    def "withTool installs via bat on Windows"() {
        given:
        def jenkins = fakeJenkins(false, [LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local", PATH: "C:\\Windows"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.withTool("MyTool", null) { }

        then:
        jenkins.calls.bat.find { it.toString().contains("dotnet tool install MyTool") } != null
        jenkins.calls.sh.isEmpty()
    }

    def "runTool runs the tool via dotnet tool run with args"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", ["--help", "--verbose"], null, false)

        then:
        def runCall = jenkins.calls.sh.find { it instanceof Map && it.script == "dotnet tool run mytool -- --help --verbose" }
        runCall != null
        runCall.returnStatus == false
    }

    def "runTool threads returnStatus through"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", [], null, true)

        then:
        def runCall = jenkins.calls.sh.find { it instanceof Map && it.script == "dotnet tool run mytool --" }
        runCall.returnStatus == true
    }

    def "runTool uses bat on Windows"() {
        given:
        def jenkins = fakeJenkins(false, [LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local", PATH: "C:\\Windows"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", ["arg"], null, false)

        then:
        jenkins.calls.bat.find { it instanceof Map && it.script == "dotnet tool run mytool -- arg" } != null
        jenkins.calls.sh.isEmpty()
    }

    def "runTool inserts -- so dotnet forwards option-like args to the tool instead of intercepting them"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", ["--help"], null, false)

        then:
        // without "--", `dotnet tool run mytool --help` would have dotnet's own CLI
        // parser intercept --help and print dotnet's help instead of the tool's
        // (confirmed by real execution) - "--" forces everything after it through
        // to the tool verbatim.
        jenkins.calls.sh.find { it instanceof Map && it.script == "dotnet tool run mytool -- --help" } != null
    }
}
