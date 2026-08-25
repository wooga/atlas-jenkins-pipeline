package scripts

import net.wooga.jenkins.pipeline.model.Dotnet
import tools.DeclarativeJenkinsSpec

class RunDotnetToolSpec extends DeclarativeJenkinsSpec {
    private static final String SCRIPT_PATH = "vars/runDotnetTool.groovy"

    // Mirrors Dotnet.requireDotnetCliHomeSh()/requireDotnetCliHomeBat() - every
    // dotnet invocation now prepends one of them.
    private static final String CLI_HOME_GUARD_SH = '[ -n "$DOTNET_CLI_HOME" ] || { echo "[dotnet] DOTNET_CLI_HOME is not set - refusing to run to avoid writing to the default NuGet/dotnet locations" >&2; exit 1; }'
    private static final String CLI_HOME_GUARD_BAT = 'if not defined DOTNET_CLI_HOME (echo [dotnet] DOTNET_CLI_HOME is not set - refusing to run to avoid writing to the default NuGet/dotnet locations 1>&2 & exit /b 1)'

    def setup() {
        helper.registerAllowedMethod("libraryResource", [String]) { String path -> "" }
        helper.registerAllowedMethod("powershell", [String]) { String script -> null }
        helper.registerAllowedMethod("powershell", [Map]) { Map args -> null }
        environment["HOME"] = "/home/tester"
        environment["LOCALAPPDATA"] = "C:\\Users\\tester\\AppData\\Local"
        environment["PATH"] = "/usr/bin"
        credentials.addUsernamePassword("artifactory_read", "fake-jfrog-user", "fake-jfrog-pass")
    }

    private List<Object> shArgs() {
        return calls["sh"].collect { it.args[0] }
    }

    private List<Object> batArgs() {
        return calls["bat"].collect { it.args[0] }
    }

    def "simple form installs the tool then runs it via dotnet tool run"() {
        given:
        def runDotnetTool = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox { runDotnetTool("MyTool", "mytool", ["--help", "--verbose"]) }

        then:
        shArgs().any { it instanceof Map && it.script.contains("dotnet tool install MyTool") && it.script.contains("--create-manifest-if-needed") }
        shArgs().any { it instanceof Map && it.script == "${CLI_HOME_GUARD_SH}\ndotnet tool run mytool -- --help --verbose" }
    }

    def "map form with an explicit version passes --allow-downgrade"() {
        given:
        def runDotnetTool = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox { runDotnetTool(packageId: "MyTool", toolBinary: "mytool", args: ["run"], version: "1.2.3") }

        then:
        shArgs().any { it instanceof Map && it.script.contains("--version 1.2.3") && it.script.contains("--allow-downgrade") }
        shArgs().any { it instanceof Map && it.script == "${CLI_HOME_GUARD_SH}\ndotnet tool run mytool -- run" }
    }

    def "map form threads returnStatus through"() {
        given:
        def runDotnetTool = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox { runDotnetTool(packageId: "MyTool", toolBinary: "mytool", args: [], returnStatus: true) }

        then:
        def runCall = shArgs().find { it instanceof Map && it.script == "${CLI_HOME_GUARD_SH}\ndotnet tool run mytool --" }
        runCall.returnStatus == true
    }

    def "runs via bat on Windows"() {
        given:
        helper.registerAllowedMethod("isUnix", []) { false }
        def runDotnetTool = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox { runDotnetTool("MyTool", "mytool", ["--help"]) }

        then:
        batArgs().any { it instanceof Map && it.script.contains("dotnet tool install MyTool") }
        batArgs().any { it instanceof Map && it.script == "${CLI_HOME_GUARD_BAT} & dotnet tool run mytool -- --help" }
        shArgs().isEmpty()
    }

    def "passes --help through to the tool instead of dotnet intercepting it"() {
        given:
        def runDotnetTool = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox { runDotnetTool("MyTool", "mytool", ["--help"]) }

        then:
        // Confirmed by real execution: without "--", dotnet's own CLI parser
        // intercepts --help and prints its own help instead of the tool's.
        shArgs().any { it instanceof Map && it.script == "${CLI_HOME_GUARD_SH}\ndotnet tool run mytool -- --help" }
    }

    def "map form threads stdoutFile and stderrFile through"() {
        given:
        def runDotnetTool = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox {
            runDotnetTool(packageId: "MyTool", toolBinary: "mytool", args: ["validate"],
                    stdoutFile: "out.log", stderrFile: "err.log")
        }

        then:
        shArgs().any {
            it instanceof Map && it.script == ([
                    "#!/bin/bash",
                    ': > "out.log"',
                    ': > "err.log"',
                    CLI_HOME_GUARD_SH,
                    "exec 3>&1 4>&2  # each tee below targets its own fd, so neither redirect can clobber the other's target",
                    "# Clear a stale file at a FIFO path first (from a crashed run) - tee would",
                    "# otherwise hit EOF instantly and capture nothing. A genuine mkfifo failure",
                    "# exits loudly via 125 instead of that same silent shape.",
                    'rm -f "out.log.fifo" "err.log.fifo"',
                    'mkfifo "out.log.fifo" "err.log.fifo" || exit 125',
                    'tee "out.log" >&3 < "out.log.fifo" &',
                    '_stdout_tee_pid=$!',
                    'tee "err.log" >&4 < "err.log.fifo" &',
                    '_stderr_tee_pid=$!',
                    "# No pipe, so \$? is <command>'s own exit status; closing fds 3/4 stops it",
                    "# (or any child) from holding the console descriptors open against the",
                    "# Durable Task step.",
                    'dotnet tool run mytool -- validate > "out.log.fifo" 2> "err.log.fifo" 3>&- 4>&-',
                    '_exit_code=$?',
                    'exec 3>&- 4>&-',
                    "# Bounds `wait`: a child outliving <command> while holding a FIFO open would",
                    "# hang tee (and wait) forever, so the watchdog kills it after a timeout,",
                    "# trading a hang for truncated capture. `pkill -P` (non-POSIX, but present",
                    "# on Linux and macOS) must kill the sleep before `kill` takes the watchdog,",
                    "# or the sleep is orphaned for the full timeout.",
                    "( sleep ${Dotnet.CAPTURE_OUTPUT_WATCHDOG_TIMEOUT_SECONDS}; kill \"\$_stdout_tee_pid\" \"\$_stderr_tee_pid\" 2>/dev/null ) &",
                    '_watchdog_pid=$!',
                    'wait "$_stdout_tee_pid" "$_stderr_tee_pid"',
                    'pkill -P "$_watchdog_pid" 2>/dev/null; kill "$_watchdog_pid" 2>/dev/null',
                    'rm -f "out.log.fifo" "err.log.fifo"',
                    'exit $_exit_code',
            ].join("\n"))
        }
        // only the FIFOs are cleaned up - the capture files belong to the caller
        shArgs().any { it instanceof Map && it.script == 'rm -f "out.log.fifo" "err.log.fifo"' }
    }

    def "map form threads stderrFile through on its own"() {
        given:
        def runDotnetTool = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox { runDotnetTool(packageId: "MyTool", toolBinary: "mytool", args: ["validate"], stderrFile: "err.log") }

        then:
        shArgs().any {
            it instanceof Map && it.script == ([
                    "#!/bin/bash",
                    ': > "err.log"',
                    CLI_HOME_GUARD_SH,
                    "exec 3>&1 4>&2  # each tee below targets its own fd, so neither redirect can clobber the other's target",
                    "# Clear a stale file at a FIFO path first (from a crashed run) - tee would",
                    "# otherwise hit EOF instantly and capture nothing. A genuine mkfifo failure",
                    "# exits loudly via 125 instead of that same silent shape.",
                    'rm -f "err.log.fifo"',
                    'mkfifo "err.log.fifo" || exit 125',
                    'tee "err.log" >&4 < "err.log.fifo" &',
                    '_stderr_tee_pid=$!',
                    "# No pipe, so \$? is <command>'s own exit status; closing fds 3/4 stops it",
                    "# (or any child) from holding the console descriptors open against the",
                    "# Durable Task step.",
                    'dotnet tool run mytool -- validate 2> "err.log.fifo" 3>&- 4>&-',
                    '_exit_code=$?',
                    'exec 3>&- 4>&-',
                    "# Bounds `wait`: a child outliving <command> while holding a FIFO open would",
                    "# hang tee (and wait) forever, so the watchdog kills it after a timeout,",
                    "# trading a hang for truncated capture. `pkill -P` (non-POSIX, but present",
                    "# on Linux and macOS) must kill the sleep before `kill` takes the watchdog,",
                    "# or the sleep is orphaned for the full timeout.",
                    "( sleep ${Dotnet.CAPTURE_OUTPUT_WATCHDOG_TIMEOUT_SECONDS}; kill \"\$_stderr_tee_pid\" 2>/dev/null ) &",
                    '_watchdog_pid=$!',
                    'wait "$_stderr_tee_pid"',
                    'pkill -P "$_watchdog_pid" 2>/dev/null; kill "$_watchdog_pid" 2>/dev/null',
                    'rm -f "err.log.fifo"',
                    'exit $_exit_code',
            ].join("\n"))
        }
    }

    def "map form threads loginShell, umask and logCommandToStdErr through"() {
        given:
        def runDotnetTool = loadSandboxedScript(SCRIPT_PATH)

        when:
        inSandbox {
            runDotnetTool(packageId: "MyTool", toolBinary: "mytool", args: ["arg"],
                    loginShell: true, umask: "002", logCommandToStdErr: true)
        }

        then:
        shArgs().any {
            it instanceof Map && it.script == "#!/bin/bash -l\nexport PATH=\"\$DOTNET_ROOT:\$PATH\"\nset -x\numask 002\n${CLI_HOME_GUARD_SH}\ndotnet tool run mytool -- arg"
        }
    }
}
