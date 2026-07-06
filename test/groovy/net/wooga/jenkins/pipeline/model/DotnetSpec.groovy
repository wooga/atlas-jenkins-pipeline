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

    def "resolves unix cache dir from HOME"() {
        given:
        def dotnet = new Dotnet(fakeJenkins(true, [HOME: "/home/tester"]))

        expect:
        dotnet.cacheDir() == "/home/tester/.cache/dotnet"
    }

    def "resolves windows cache dir from LOCALAPPDATA"() {
        given:
        def dotnet = new Dotnet(fakeJenkins(false, [LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local"]))

        expect:
        dotnet.cacheDir() == "C:\\Users\\tester\\AppData\\Local\\cache\\dotnet"
    }

    @Unroll
    def "provision() sets selector env DOTNET_INSTALL_DIR plus #expectedExtra"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester"], hasGlobalJson)
        def dotnet = new Dotnet(jenkins, version, channel, globalJson)

        when:
        dotnet.provision()

        then:
        jenkins.calls.withEnv == [["DOTNET_INSTALL_DIR=/home/tester/.cache/dotnet"] + expectedExtra]
        jenkins.calls.libraryResource == ["dotnet/dotnet-install.sh"]
        jenkins.calls.writeFile[0].file == ".ci/dotnet-install.sh"
        jenkins.calls.sh.size() == 1

        where:
        version   | channel | globalJson         | hasGlobalJson | expectedExtra
        "8.0.401" | null    | null               | false         | ["DOTNET_VERSION=8.0.401"]
        null      | "8.0"   | null               | false         | ["DOTNET_CHANNEL=8.0"]
        null      | null    | "path/global.json" | false         | ["GLOBAL_JSON=path/global.json"]
        null      | null    | null               | true          | [] // workspace global.json found: let the script auto-detect it
        null      | null    | null               | false         | ["DOTNET_DEFAULT_VERSION=${Dotnet.DEFAULT_VERSION}"]
    }

    def "provision() uses the powershell wrapper on Windows"() {
        given:
        def jenkins = fakeJenkins(false, [LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.provision()

        then:
        jenkins.calls.libraryResource == ["dotnet/dotnet-install.ps1"]
        jenkins.calls.writeFile[0].file == ".ci/dotnet-install.ps1"
        jenkins.calls.powershell.size() == 1
        jenkins.calls.sh.isEmpty()
    }

    def "withEnvList exposes the cache dir on PATH and as DOTNET_ROOT"() {
        given:
        def dotnet = new Dotnet(fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"]))

        expect:
        dotnet.withEnvList() == ["DOTNET_ROOT=/home/tester/.cache/dotnet", "PATH=/home/tester/.cache/dotnet:/usr/bin"]
    }

    def "withEnvList uses a semicolon PATH separator on Windows"() {
        given:
        def dotnet = new Dotnet(fakeJenkins(false, [LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local", PATH: "C:\\Windows"]))

        expect:
        dotnet.withEnvList() == ["DOTNET_ROOT=C:\\Users\\tester\\AppData\\Local\\cache\\dotnet", "PATH=C:\\Users\\tester\\AppData\\Local\\cache\\dotnet;C:\\Windows"]
    }

    def "withProvisionedEnv provisions the SDK then runs the block inside the provisioned env"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins)
        def ran = false

        when:
        dotnet.withProvisionedEnv { ran = true }

        then:
        ran
        jenkins.calls.withEnv.size() == 2
        jenkins.calls.withEnv[1] == [
                "DOTNET_ROOT=/home/tester/.cache/dotnet",
                "PATH=/home/tester/.cache/dotnet:/usr/bin",
                "NuGetPackageSourceCredentials_wooga_nuget=Username=fake-jfrog-user;Password=fake-jfrog-pass"
        ]
        // one sh call for the install wrapper, one for the idempotent nuget source registration
        jenkins.calls.sh.size() == 2
    }

    def "withProvisionedEnv binds the shared artifactory_read credential"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.withProvisionedEnv { }

        then:
        jenkins.calls.withCredentials.size() == 1
        jenkins.calls.withCredentials[0] == [[
                credentialsId: Dotnet.NUGET_CREDENTIALS_ID, usernameVariable: 'JFROG_USER', passwordVariable: 'JFROG_PASS'
        ]]
    }

    def "withProvisionedEnv registers the shared wooga_nuget source idempotently"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.withProvisionedEnv { }

        then:
        def nugetCall = jenkins.calls.sh.find { it.toString().contains("nuget add source") }
        nugetCall != null
        nugetCall.contains(Dotnet.NUGET_SOURCE_URL)
        nugetCall.contains(Dotnet.NUGET_SOURCE_NAME)
        nugetCall.contains("dotnet nuget list source") // checks before adding, for idempotency
    }

    def "withProvisionedEnv registers the nuget source via powershell on Windows"() {
        given:
        def jenkins = fakeJenkins(false, [LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local", PATH: "C:\\Windows"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.withProvisionedEnv { }

        then:
        def nugetCall = jenkins.calls.powershell.find { it.toString().contains("nuget add source") }
        nugetCall != null
        nugetCall.contains(Dotnet.NUGET_SOURCE_URL)
        nugetCall.contains(Dotnet.NUGET_SOURCE_NAME)
    }

    def "toolCacheDir resolves under cacheDir on unix"() {
        given:
        def dotnet = new Dotnet(fakeJenkins(true, [HOME: "/home/tester"]))

        expect:
        dotnet.toolCacheDir() == "/home/tester/.cache/dotnet/tools"
    }

    def "toolCacheDir resolves under cacheDir on Windows"() {
        given:
        def dotnet = new Dotnet(fakeJenkins(false, [LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local"]))

        expect:
        dotnet.toolCacheDir() == "C:\\Users\\tester\\AppData\\Local\\cache\\dotnet\\tools"
    }

    def "withTool provisions, installs the tool, then runs the block"() {
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
        toolEnvCall.any { it.toString() == "NUGET_PACKAGES=/home/tester/.cache/dotnet/tools/packages" }
        toolEnvCall.any { it.toString() == "DOTNET_CLI_HOME=/home/tester/.cache/dotnet/tools" }
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
        def runCall = jenkins.calls.sh.find { it instanceof Map && it.script == "dotnet tool run mytool --help --verbose" }
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
        def runCall = jenkins.calls.sh.find { it instanceof Map && it.script == "dotnet tool run mytool" }
        runCall.returnStatus == true
    }

    def "runTool uses bat on Windows"() {
        given:
        def jenkins = fakeJenkins(false, [LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local", PATH: "C:\\Windows"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", ["arg"], null, false)

        then:
        jenkins.calls.bat.find { it instanceof Map && it.script == "dotnet tool run mytool arg" } != null
        jenkins.calls.sh.isEmpty()
    }
}
