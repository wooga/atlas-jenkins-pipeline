package net.wooga.jenkins.pipeline.model

import spock.lang.Specification
import spock.lang.Unroll

class DotnetSpec extends Specification {

    // Mirrors Dotnet.requireDotnetCliHomeSh()/requireDotnetCliHomeBat() - kept
    // as literal constants here since those are private and every dotnet
    // invocation now prepends one of them, so most sh/bat script assertions
    // below need to account for it.
    static final String CLI_HOME_GUARD_SH = '[ -n "$DOTNET_CLI_HOME" ] || { echo "[dotnet] DOTNET_CLI_HOME is not set - refusing to run to avoid writing to the default NuGet/dotnet locations" >&2; exit 1; }'
    static final String CLI_HOME_GUARD_BAT = 'if not defined DOTNET_CLI_HOME (echo [dotnet] DOTNET_CLI_HOME is not set - refusing to run to avoid writing to the default NuGet/dotnet locations 1>&2 & exit /b 1)'

    static Expando fakeJenkins(boolean unix, Map<String, String> env = [:], boolean hasGlobalJson = false) {
        def jenkins = new Expando()
        jenkins.calls = [withEnv: [], sh: [], bat: [], powershell: [], writeFile: [], libraryResource: [], withCredentials: [], readFile: [], isUnix: 0]
        jenkins.isUnix = { -> jenkins.calls.isUnix++; unix }
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
        // Kept for completeness only: capture files are read back by the caller
        // (see runDotnetTool.txt), not by Dotnet.groovy.
        jenkins.readFile = { Map args -> jenkins.calls.readFile << args.file; return "" }
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

    def "withEnvList exposes the cache dir on PATH/DOTNET_ROOT/DOTNET_BIN and always redirects NUGET_PACKAGES/DOTNET_CLI_HOME"() {
        given:
        def dotnet = new Dotnet(fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"]))

        expect:
        dotnet.withEnvList() == [
                "DOTNET_ROOT=/home/tester/.cache/jenkins-pipeline/dotnet",
                "PATH=/home/tester/.cache/jenkins-pipeline/dotnet:/usr/bin",
                "DOTNET_BIN=/home/tester/.cache/jenkins-pipeline/dotnet/dotnet",
                "NUGET_PACKAGES=/home/tester/.cache/jenkins-pipeline/dotnet/tools/packages",
                "DOTNET_CLI_HOME=/home/tester/.cache/jenkins-pipeline/dotnet/tools",
        ]
    }

    def "withEnvList uses a semicolon PATH separator and dotnet.exe on Windows"() {
        given:
        def dotnet = new Dotnet(fakeJenkins(false, [LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local", PATH: "C:\\Windows"]))

        expect:
        dotnet.withEnvList() == [
                "DOTNET_ROOT=C:\\Users\\tester\\AppData\\Local\\cache\\jenkins-pipeline\\dotnet",
                "PATH=C:\\Users\\tester\\AppData\\Local\\cache\\jenkins-pipeline\\dotnet;C:\\Windows",
                "DOTNET_BIN=C:\\Users\\tester\\AppData\\Local\\cache\\jenkins-pipeline\\dotnet\\dotnet.exe",
                "NUGET_PACKAGES=C:\\Users\\tester\\AppData\\Local\\cache\\jenkins-pipeline\\dotnet\\tools\\packages",
                "DOTNET_CLI_HOME=C:\\Users\\tester\\AppData\\Local\\cache\\jenkins-pipeline\\dotnet\\tools",
        ]
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
        jenkins.calls.withEnv == [[
                "DOTNET_ROOT=/home/tester/.cache/jenkins-pipeline/dotnet",
                "PATH=/home/tester/.cache/jenkins-pipeline/dotnet:/usr/bin",
                "DOTNET_BIN=/home/tester/.cache/jenkins-pipeline/dotnet/dotnet",
                "NUGET_PACKAGES=/home/tester/.cache/jenkins-pipeline/dotnet/tools/packages",
                "DOTNET_CLI_HOME=/home/tester/.cache/jenkins-pipeline/dotnet/tools",
        ]]
    }

    def "withInstalledDotnet registers a NuGet source without binding credentials when only the source is given"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins, null, null, null, "my_source", "https://example.com/index.json", null)

        when:
        dotnet.withInstalledDotnet { }

        then:
        jenkins.calls.withCredentials.isEmpty()
        def nugetCall = jenkins.calls.sh.find { it instanceof Map && it.script.contains("nuget add source") }
        nugetCall != null
        nugetCall.script.contains("https://example.com/index.json")
        nugetCall.script.contains("my_source")
        nugetCall.script.contains("dotnet nuget list source") // checks before adding, for idempotency
        nugetCall.script.contains("--configfile ./nuget.config") // registers into a local, workspace-relative config...
        nugetCall.script.contains("dotnet new nugetconfig") // ...creating it first if it doesn't already exist
        !nugetCall.script.contains("--username") // never write credentials into the file - see nugetCredentialsEnv()
        !nugetCall.script.contains("--store-password-in-clear-text")
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
        def nugetCall = jenkins.calls.sh.find { it instanceof Map && it.script.contains("nuget add source") }
        nugetCall != null
        nugetCall.script.contains("https://example.com/index.json")
        nugetCall.script.contains("my_source")
        nugetCall.script.contains("--configfile ./nuget.config")
        // credentials flow exclusively via the env var below, never as CLI args on the
        // nuget add source call itself, regardless of which NuGet.Config registered the source
        !nugetCall.script.contains("--username")
        !nugetCall.script.contains("--store-password-in-clear-text")
        jenkins.calls.withEnv[0].any { it.toString() == "NuGetPackageSourceCredentials_my_source=Username=fake-jfrog-user;Password=fake-jfrog-pass" }
    }

    def "withInstalledDotnet registers the NuGet source via powershell on Windows"() {
        given:
        def jenkins = fakeJenkins(false, [LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local", PATH: "C:\\Windows"])
        def dotnet = new Dotnet(jenkins, null, null, null, "my_source", "https://example.com/index.json", "my_creds")

        when:
        dotnet.withInstalledDotnet { }

        then:
        def nugetCall = jenkins.calls.powershell.find { it instanceof Map && it.script.contains("nuget add source") }
        nugetCall != null
        nugetCall.script.contains("https://example.com/index.json")
        nugetCall.script.contains("my_source")
        nugetCall.script.contains("--configfile ./nuget.config")
        nugetCall.script.contains("dotnet new nugetconfig")
        !nugetCall.script.contains("--username")
        !nugetCall.script.contains("--store-password-in-clear-text")
        jenkins.calls.sh.isEmpty() // no unix sh lock on Windows (uses powershell/bat only)
    }

    def "withInstalledDotnet serializes concurrent NuGet source registration by acquiring a workspace lock"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins, null, null, null, "my_source", "https://example.com/index.json", null)

        when:
        dotnet.withInstalledDotnet { }

        then: "acquisition is a separate sh (via Lockfile) running an atomic mkdir loop on a workspace-relative lock dir"
        def acquire = jenkins.calls.sh.find { it instanceof Map && it.script.contains('_LOCK_DIR="./nuget.config.lock"') }
        acquire != null
        acquire.script.contains('while ! mkdir "$_LOCK_DIR" 2>/dev/null; do')
        acquire.script.contains('breaking stale lock') // shared Lockfile gives this a stale-break timeout too now
        acquire.script.contains('Acquired NuGet config lock')

        and: "the check-then-create-then-add sequence runs unlocked (the lock is the surrounding Lockfile, not inline)"
        def addCall = jenkins.calls.sh.find { it instanceof Map && it.script.contains("dotnet nuget list source") }
        addCall != null
        addCall.script.contains("dotnet new nugetconfig")
        !addCall.script.contains("_LOCK_DIR")

        and: "the lock is released afterward"
        jenkins.calls.sh.any { it instanceof Map && it.script.contains("Released NuGet config lock") }
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
        def installCall = jenkins.calls.sh.find { it instanceof Map && it.script.contains("dotnet tool install MyTool") }
        installCall != null
        installCall.script.contains("--create-manifest-if-needed")
        !installCall.script.contains("--version")
        !installCall.script.contains("--allow-downgrade")
    }

    def "withTool passes version and --allow-downgrade when a version is given"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.withTool("MyTool", "1.2.3") { }

        then:
        def installCall = jenkins.calls.sh.find { it instanceof Map && it.script.contains("dotnet tool install MyTool") }
        installCall.script.contains("--version 1.2.3")
        installCall.script.contains("--allow-downgrade")
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

    def "withTool registers the NuGet source exactly once, visible to installTool/runTool since there's only one DOTNET_CLI_HOME scope"() {
        given: "NUGET_PACKAGES/DOTNET_CLI_HOME are unconditionally part of withEnvList() now (nugetHomeEnv()), so" +
                " withInstalledDotnet's ensureNuGetSource() call already runs in the exact same scope installTool()/" +
                " runTool() will use - no second, tool-specific registration call is needed anymore"
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins, null, null, null, "wooga_nuget", "https://example.com/index.json", null)

        when:
        dotnet.withTool("MyTool", null) { }

        then:
        jenkins.calls.sh.count { it.toString().contains("nuget add source") } == 1
    }

    def "withTool registers no NuGet source when none was configured"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.withTool("MyTool", null) { }

        then:
        jenkins.calls.sh.count { it.toString().contains("nuget add source") } == 0
    }

    def "withTool installs via bat on Windows"() {
        given:
        def jenkins = fakeJenkins(false, [LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local", PATH: "C:\\Windows"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.withTool("MyTool", null) { }

        then:
        jenkins.calls.bat.find { it instanceof Map && it.script.contains("dotnet tool install MyTool") } != null
        jenkins.calls.sh.isEmpty()
    }

    def "withTool serializes concurrent installs by acquiring an agent-wide lock"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.withTool("MyTool", null) { }

        then: "acquisition is a separate sh (via Lockfile) running an atomic mkdir loop on an agent-wide lock dir"
        def acquire = jenkins.calls.sh.find { it instanceof Map && it.script.contains('_LOCK_DIR="/home/tester/.cache/jenkins-pipeline/dotnet/tools.tool-install.lock"') }
        acquire != null
        acquire.script.contains('while ! mkdir "$_LOCK_DIR" 2>/dev/null; do')
        acquire.script.contains('breaking stale lock')
        acquire.script.contains('Acquired tool install lock')

        and: "the install runs unlocked (the lock is the surrounding Lockfile, not inline)"
        def installCall = jenkins.calls.sh.find { it instanceof Map && it.script.contains("dotnet tool install MyTool") }
        installCall != null
        installCall.script.contains("--create-manifest-if-needed")
        !installCall.script.contains("_LOCK_DIR")

        and: "the lock is released afterward"
        jenkins.calls.sh.any { it instanceof Map && it.script.contains("Released tool install lock") }
    }

    def "withTool does not lock on Windows"() {
        given:
        def jenkins = fakeJenkins(false, [LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local", PATH: "C:\\Windows"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.withTool("MyTool", null) { }

        then:
        def installCall = jenkins.calls.bat.find { it instanceof Map && it.script.contains("dotnet tool install MyTool") }
        installCall != null
        !installCall.script.contains("mkdir")
        jenkins.calls.sh.isEmpty() // no unix sh lock on Windows
    }

    def "runTool runs the tool via dotnet tool run with args"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", ["--help", "--verbose"], null, false)

        then:
        def runCall = jenkins.calls.sh.find { it instanceof Map && it.script == "${CLI_HOME_GUARD_SH}\ndotnet tool run mytool -- --help --verbose" }
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
        def runCall = jenkins.calls.sh.find { it instanceof Map && it.script == "${CLI_HOME_GUARD_SH}\ndotnet tool run mytool --" }
        runCall.returnStatus == true
    }

    def "runTool uses bat on Windows"() {
        given:
        def jenkins = fakeJenkins(false, [LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local", PATH: "C:\\Windows"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", ["arg"], null, false)

        then:
        jenkins.calls.bat.find { it instanceof Map && it.script == "${CLI_HOME_GUARD_BAT} & dotnet tool run mytool -- arg" } != null
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
        jenkins.calls.sh.find { it instanceof Map && it.script == "${CLI_HOME_GUARD_SH}\ndotnet tool run mytool -- --help" } != null
    }

    def "runTool defaults to a plain command with no loginShell/umask/logCommandToStdErr"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", ["arg"], null, false)

        then:
        jenkins.calls.sh.find { it instanceof Map && it.script == "${CLI_HOME_GUARD_SH}\ndotnet tool run mytool -- arg" } != null
    }

    def "runTool with loginShell prepends a #!/bin/bash -l shebang and re-exports PATH"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", ["arg"], null, false, [loginShell: true])

        then:
        // the shebang must be the very first line for Jenkins' sh step to honour it;
        // the PATH re-export defends against a login shell's profile-sourcing
        // clobbering PATH before the tool command runs (confirmed by real execution:
        // without it, a profile resetting PATH makes `dotnet` unresolvable).
        jenkins.calls.sh.find {
            it instanceof Map && it.script == "#!/bin/bash -l\nexport PATH=\"\$DOTNET_ROOT:\$PATH\"\n${CLI_HOME_GUARD_SH}\ndotnet tool run mytool -- arg"
        } != null
    }

    def "runTool with logCommandToStdErr prepends set -x"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", ["arg"], null, false, [logCommandToStdErr: true])

        then:
        jenkins.calls.sh.find { it instanceof Map && it.script == "set -x\n${CLI_HOME_GUARD_SH}\ndotnet tool run mytool -- arg" } != null
    }

    def "runTool with umask prepends the umask command"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", ["arg"], null, false, [umask: "002"])

        then:
        jenkins.calls.sh.find { it instanceof Map && it.script == "umask 002\n${CLI_HOME_GUARD_SH}\ndotnet tool run mytool -- arg" } != null
    }

    def "runTool combines loginShell, logCommandToStdErr and umask in the correct order"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", ["arg"], null, false, [loginShell: true, umask: "002", logCommandToStdErr: true])

        then:
        // shebang + PATH re-export must lead; logCommandToStdErr before umask so the umask command itself is traced too;
        // the DOTNET_CLI_HOME guard comes last, immediately before the actual command
        jenkins.calls.sh.find {
            it instanceof Map && it.script == "#!/bin/bash -l\nexport PATH=\"\$DOTNET_ROOT:\$PATH\"\nset -x\numask 002\n${CLI_HOME_GUARD_SH}\ndotnet tool run mytool -- arg"
        } != null
    }

    def "runTool ignores loginShell/umask/logCommandToStdErr on Windows"() {
        given:
        def jenkins = fakeJenkins(false, [LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local", PATH: "C:\\Windows"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", ["arg"], null, false, [loginShell: true, umask: "002", logCommandToStdErr: true])

        then:
        jenkins.calls.bat.find { it instanceof Map && it.script == "${CLI_HOME_GUARD_BAT} & dotnet tool run mytool -- arg" } != null
        jenkins.calls.sh.isEmpty()
    }

    def "runTool duplicates both streams into the caller-named files and passes the exit code through"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        jenkins.sh = { Object arg ->
            jenkins.calls.sh << arg
            (arg instanceof Map && arg.script?.contains("dotnet tool run mytool")) ? 1 : 0
        }
        def dotnet = new Dotnet(jenkins)

        when:
        def result = dotnet.runTool("MyTool", "mytool", ["validate"], null, true,
                [stdoutFile: "out.log", stderrFile: "err.log"])

        then:
        result == 1
        def runCall = jenkins.calls.sh.find { it instanceof Map && it.script.contains("dotnet tool run mytool -- validate") }
        // These assertions pin the generated script shape line by line - see
        // captureOutputScriptSh() for why each line is load-bearing.
        runCall.script.startsWith("#!/bin/bash\n")
        // truncation before the guard, so an early exit can't leave an earlier build's file behind
        runCall.script.contains(": > \"out.log\"\n: > \"err.log\"\n${CLI_HOME_GUARD_SH}\nexec 3>&1 4>&2")
        runCall.script.contains('rm -f "out.log.fifo" "err.log.fifo"\nmkfifo "out.log.fifo" "err.log.fifo" || exit 125')
        runCall.script.contains('tee "out.log" >&3 < "out.log.fifo" &')
        runCall.script.contains('_stdout_tee_pid=$!')
        runCall.script.contains('tee "err.log" >&4 < "err.log.fifo" &')
        runCall.script.contains('_stderr_tee_pid=$!')
        runCall.script.contains('dotnet tool run mytool -- validate > "out.log.fifo" 2> "err.log.fifo" 3>&- 4>&-')
        runCall.script.contains('_exit_code=$?')
        runCall.script.contains('exec 3>&- 4>&-')
        // Interpolates the real constant rather than a hardcoded literal, so a
        // timeout change can't silently desync this assertion from the behavior.
        runCall.script.contains("( sleep ${Dotnet.CAPTURE_OUTPUT_WATCHDOG_TIMEOUT_SECONDS}; kill \"\$_stdout_tee_pid\" \"\$_stderr_tee_pid\" 2>/dev/null ) &")
        runCall.script.contains('_watchdog_pid=$!')
        runCall.script.contains('wait "$_stdout_tee_pid" "$_stderr_tee_pid"')
        runCall.script.contains('pkill -P "$_watchdog_pid" 2>/dev/null; kill "$_watchdog_pid" 2>/dev/null')
        runCall.script.contains('rm -f "out.log.fifo" "err.log.fifo"\nexit $_exit_code')
        // the library never reads the capture files - that's the caller's job
        jenkins.calls.readFile.isEmpty()
    }

    def "runTool keeps sh()'s throw-on-failure contract when capturing without returnStatus"() {
        given: "capture no longer swallows a nonzero exit - the caller's returnStatus choice is passed straight through"
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        jenkins.sh = { Object arg -> jenkins.calls.sh << arg; 0 }
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", [], null, false, [stderrFile: "err.log"])

        then:
        def runCall = jenkins.calls.sh.find { it instanceof Map && it.script.contains("dotnet tool run") }
        runCall.returnStatus == false
    }

    def "runTool threads returnStatus through a capturing call"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        jenkins.sh = { Object arg -> jenkins.calls.sh << arg; 0 }
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", [], null, true, [stderrFile: "err.log"])

        then:
        def runCall = jenkins.calls.sh.find { it instanceof Map && it.script.contains("dotnet tool run") }
        runCall.returnStatus == true
    }

    def "runTool capturing stderr alone leaves stdout entirely untouched"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        jenkins.sh = { Object arg -> jenkins.calls.sh << arg; 0 }
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", ["validate"], null, false, [stderrFile: "err.log"])

        then: "no stdout FIFO, tee, or redirection is generated at all"
        def runCall = jenkins.calls.sh.find { it instanceof Map && it.script.contains("dotnet tool run") }
        runCall.script.contains(': > "err.log"')
        runCall.script.contains('rm -f "err.log.fifo"\nmkfifo "err.log.fifo" || exit 125')
        runCall.script.contains('tee "err.log" >&4 < "err.log.fifo" &')
        runCall.script.contains('dotnet tool run mytool -- validate 2> "err.log.fifo" 3>&- 4>&-')
        runCall.script.contains("( sleep ${Dotnet.CAPTURE_OUTPUT_WATCHDOG_TIMEOUT_SECONDS}; kill \"\$_stderr_tee_pid\" 2>/dev/null ) &")
        runCall.script.contains('wait "$_stderr_tee_pid"')
        !runCall.script.contains("_stdout_tee_pid")
        !runCall.script.contains("out.log")
        !runCall.script.contains('> "err.log.fifo" 2>')
    }

    def "runTool capturing stdout alone leaves stderr entirely untouched"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        jenkins.sh = { Object arg -> jenkins.calls.sh << arg; 0 }
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", ["validate"], null, false, [stdoutFile: "out.log"])

        then:
        def runCall = jenkins.calls.sh.find { it instanceof Map && it.script.contains("dotnet tool run") }
        runCall.script.contains('tee "out.log" >&3 < "out.log.fifo" &')
        runCall.script.contains('dotnet tool run mytool -- validate > "out.log.fifo" 3>&- 4>&-')
        runCall.script.contains('wait "$_stdout_tee_pid"')
        !runCall.script.contains("_stderr_tee_pid")
        !runCall.script.contains("2> ")
    }

    def "runTool truncates the capture files after umask so they get the caller's intended permissions"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        jenkins.sh = { Object arg -> jenkins.calls.sh << arg; 0 }
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", [], null, false,
                [umask: "002", logCommandToStdErr: true, stderrFile: "err.log"])

        then:
        def runCall = jenkins.calls.sh.find { it instanceof Map && it.script.contains("dotnet tool run") }
        runCall.script.startsWith("#!/bin/bash\nset -x\numask 002\n: > \"err.log\"\n${CLI_HOME_GUARD_SH}\n")
    }

    def "runTool with a capture file and loginShell uses a login bash shebang and re-exports PATH"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        jenkins.sh = { Object arg -> jenkins.calls.sh << arg; 0 }
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", [], null, false, [loginShell: true, stderrFile: "err.log"])

        then:
        def runCall = jenkins.calls.sh.find { it instanceof Map && it.script.contains("dotnet tool run mytool") }
        runCall.script.startsWith("#!/bin/bash -l\nexport PATH=\"\$DOTNET_ROOT:\$PATH\"\n")
    }

    def "runTool removes only the FIFOs after a failing run, leaving the caller's capture files in place"() {
        given: "the capture files must outlive the call - a post block reads them after the stage failed"
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        jenkins.sh = { Object arg ->
            jenkins.calls.sh << arg
            (arg instanceof Map && arg.script?.contains("dotnet tool run")) ? 1 : 0
        }
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", [], null, true, [stdoutFile: "out.log", stderrFile: "err.log"])

        then:
        jenkins.calls.sh.any { it instanceof Map && it.script == 'rm -f "out.log.fifo" "err.log.fifo"' }
        !jenkins.calls.sh.any { it instanceof Map && it.script == 'rm -f "out.log" "err.log" "out.log.fifo" "err.log.fifo"' }
    }

    def "runTool removes only the FIFOs after a succeeding run"() {
        given:
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        jenkins.sh = { Object arg -> jenkins.calls.sh << arg; 0 }
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", [], null, false, [stderrFile: "err.log"])

        then:
        jenkins.calls.sh.any { it instanceof Map && it.script == 'rm -f "err.log.fifo"' }
    }

    def "runTool cleans up the FIFOs and still propagates the exception when the capturing sh() call itself throws"() {
        given: "the default returnStatus: false path, where a nonzero tool exit is itself an exception"
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        jenkins.sh = { Object arg ->
            jenkins.calls.sh << arg
            if (arg instanceof Map && arg.script?.contains("dotnet tool run")) {
                throw new RuntimeException("script returned exit code 1")
            }
            0
        }
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", [], null, false, [stderrFile: "err.log"])

        then:
        // the real cause must propagate, not get masked by (or lost behind) the
        // cleanup step - see the try/catch around the cleanup sh() call.
        RuntimeException ex = thrown(RuntimeException)
        ex.message == "script returned exit code 1"
        jenkins.calls.sh.any { it instanceof Map && it.script == 'rm -f "err.log.fifo"' }
    }

    def "runTool rejects naming the same file for both streams"() {
        given: "one file for both streams would interleave them, and both FIFOs would collide on the same path"
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", [], null, false, [stdoutFile: "both.log", stderrFile: "both.log"])

        then:
        thrown(IllegalArgumentException)
        jenkins.calls.withEnv.isEmpty() // fails before attempting any install/run work
    }

    @Unroll
    def "runTool rejects a capture file path containing #description"() {
        given: "paths are validated, never sanitized - the caller named this exact file and will read it back"
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", [], null, false, [stderrFile: path])

        then:
        thrown(IllegalArgumentException)
        jenkins.calls.withEnv.isEmpty()

        where:
        description                   | path
        "a double quote"              | 'err".log'
        "a dollar sign"               | 'err-$HOME.log'
        "a backtick"                  | 'err-`whoami`.log'
        "a backslash"                 | 'err\\.log'
        "a newline"                   | 'err.log\nrm -rf /'
        "an absolute path"            | '/tmp/err.log'
        "a parent-directory segment"  | '../err.log'
        "only whitespace"             | '   '
    }

    def "runTool accepts a capture file in a workspace subdirectory"() {
        given: "a relative path with directory segments is fine - only absolute paths and .. are rejected"
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        jenkins.sh = { Object arg -> jenkins.calls.sh << arg; 0 }
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", [], null, false, [stderrFile: "build/reports/err.log"])

        then:
        noExceptionThrown()
        def runCall = jenkins.calls.sh.find { it instanceof Map && it.script.contains("dotnet tool run") }
        runCall.script.contains('tee "build/reports/err.log" >&4 < "build/reports/err.log.fifo" &')
    }

    def "runTool rejects capture files on a non-unix agent"() {
        given: "silently producing no file for a caller that is about to readFile it would surface far from the mistake"
        def jenkins = fakeJenkins(false, [LOCALAPPDATA: "C:\\Users\\tester\\AppData\\Local", PATH: "C:\\Windows"])
        def dotnet = new Dotnet(jenkins)

        when:
        dotnet.runTool("MyTool", "mytool", [], null, false, [stderrFile: "err.log"])

        then:
        thrown(IllegalArgumentException)
        jenkins.calls.withEnv.isEmpty() // fails before attempting the Windows path
        jenkins.calls.bat.isEmpty()
    }

    def "isUnix() memoizes the underlying jenkins.isUnix() call across every internal check"() {
        given: "a Dotnet exercising install, NuGet source registration, and tool install/run in one call chain"
        def jenkins = fakeJenkins(true, [HOME: "/home/tester", PATH: "/usr/bin"])
        def dotnet = new Dotnet(jenkins, null, null, null, "wooga_nuget", "https://example.com/index.json", "artifactory_read")

        when: "runTool touches install()/cacheDir()/withEnvList()/ensureNuGetSource()/toolCacheDir()/nugetHomeEnv()/installTool(), each of which used to call jenkins.isUnix() separately"
        dotnet.runTool("MyTool", "mytool", ["--help"], null, false)

        then: "the real jenkins.isUnix() step is invoked only once per instance, not once per call site"
        jenkins.calls.isUnix == 1
    }
}
