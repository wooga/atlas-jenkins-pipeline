package net.wooga.jenkins.pipeline.model

import com.cloudbees.groovy.cps.NonCPS
import net.wooga.jenkins.pipeline.cache.LockDir

/**
 * Installs the .NET SDK into a shared per-agent cache directory (via the
 * vendored dotnet-install wrapper scripts) and exposes it to callers. Generic
 * with respect to NuGet: source/credentials are optional constructor
 * parameters with no built-in default - callers (see vars/withDotnet.groovy,
 * vars/dotnetWrapper.groovy) are responsible for supplying org-specific
 * defaults.
 */
class Dotnet {

    /**
     * Org-wide default SDK version, used when a caller gives no selector and
     * no global.json is found in the workspace. Kept as an exact, pinned
     * version (not a floating channel/LTS) so builds without a global.json
     * get perfect, deterministic cache reuse across the fleet. Bump via a PR
     * when the org wants to move the default forward.
     */
    static final String DEFAULT_VERSION = "10.0.301"

    /**
     * Bounds how long a stdoutFile/stderrFile capture can be blocked by a
     * hung/lingering child of the tool (see captureOutputScriptSh()) before
     * giving up and leaving truncated output in the capture file rather than
     * hanging the step indefinitely.
     */
    static final int CAPTURE_OUTPUT_WATCHDOG_TIMEOUT_SECONDS = 300

    private Object jenkins
    // Not named "unix" - that would form a Groovy JavaBean property pair with
    // isUnix() below, and referencing the bare field name inside its own
    // property's getter recurses back into the getter instead of reading the
    // field, infinitely (confirmed by real execution: a StackOverflowError).
    private Boolean unixCache
    private String version
    private String channel
    private String globalJson
    private String nugetSourceName
    private String nugetSourceUrl
    private String nugetCredentialsId

    static Dotnet fromJenkins(Object jenkinsScript, Map args = [:]) {
        return new Dotnet(jenkinsScript, args.version as String, args.channel as String, args.globalJson as String,
                args.nugetSourceName as String, args.nugetSourceUrl as String, args.nugetCredentialsId as String)
    }

    Dotnet(Object jenkins, String version = null, String channel = null, String globalJson = null,
           String nugetSourceName = null, String nugetSourceUrl = null, String nugetCredentialsId = null) {
        validateSelectors(version, channel, globalJson)
        validateNugetConfig(nugetSourceName, nugetSourceUrl, nugetCredentialsId)
        this.jenkins = jenkins
        this.version = version
        this.channel = channel
        this.globalJson = globalJson
        this.nugetSourceName = nugetSourceName
        this.nugetSourceUrl = nugetSourceUrl
        this.nugetCredentialsId = nugetCredentialsId
    }

    // Called from the constructor, which can't be CPS-transformed (it can't be
    // paused/resumed) - a CPS-transformed method call from a constructor would
    // leak an unhandled CpsCallableInvocation instead of actually running.
    @NonCPS
    private static void validateSelectors(String version, String channel, String globalJson) {
        def given = [version: version, channel: channel, globalJson: globalJson].findAll { k, v -> v }
        if (given.size() > 1) {
            throw new IllegalArgumentException(
                    "dotnet SDK selector is ambiguous: only one of 'version', 'channel', or 'globalJson' may be given (got: ${given.keySet()})")
        }
    }

    @NonCPS
    private static void validateNugetConfig(String nugetSourceName, String nugetSourceUrl, String nugetCredentialsId) {
        boolean hasSourceName = nugetSourceName as boolean
        boolean hasSourceUrl = nugetSourceUrl as boolean
        if (hasSourceName != hasSourceUrl) {
            throw new IllegalArgumentException(
                    "dotnet NuGet source is incomplete: 'nugetSourceName' and 'nugetSourceUrl' must be given together (got: nugetSourceName=${nugetSourceName}, nugetSourceUrl=${nugetSourceUrl})")
        }
        if (nugetCredentialsId && !hasSourceName) {
            throw new IllegalArgumentException(
                    "'nugetCredentialsId' was given without a NuGet source: 'nugetSourceName'/'nugetSourceUrl' are required for the credentials to have a source to authenticate")
        }
    }

    /**
     * Whether the current agent is unix-like, memoized after the first check.
     * `isUnix()` is a real Jenkins step - calling it directly at every branch
     * point (cacheDir/install/withEnvList/toolCacheDir/nugetHomeEnv/ensureNuGetSource/
     * runTool/installTool) added a "Checks if running on a Unix-like node" step
     * to the build log for each call; an agent's OS can't change mid-build, so
     * a single cached check per instance is both correct and far less noisy.
     */
    boolean isUnix() {
        if (unixCache == null) {
            unixCache = jenkins.isUnix()
        }
        return unixCache
    }

    /**
     * The shared install/cache directory for the current agent's OS:
     * ~/.cache/jenkins-pipeline/dotnet on unix,
     * %LOCALAPPDATA%\cache\jenkins-pipeline\dotnet on Windows.
     */
    String cacheDir() {
        if (isUnix()) {
            return "${jenkins.env.HOME}/.cache/jenkins-pipeline/dotnet"
        }
        return "${jenkins.env.LOCALAPPDATA}\\cache\\jenkins-pipeline\\dotnet"
    }

    /**
     * The install-dir + selector to pass to the install wrapper, as a
     * semantic map (key -> value), in precedence order: version, channel,
     * globalJson, then (if a workspace global.json exists) nothing extra -
     * letting the wrapper script auto-detect it - else defaultVersion.
     */
    private Map<String, String> installArgs() {
        Map<String, String> args = [installDir: cacheDir()]
        if (version) {
            args.version = version
        } else if (channel) {
            args.channel = channel
        } else if (globalJson) {
            args.globalJson = globalJson
        } else if (jenkins.fileExists('global.json')) {
            // Let the wrapper script auto-detect the workspace global.json and
            // install its sdk.version's major.minor as a floating channel
            // (matches how GitHub Actions' setup-dotnet resolves global.json).
        } else {
            args.defaultVersion = DEFAULT_VERSION
        }
        return args
    }

    // Deliberately a plain sequence of ifs/string-building, not a closure-based
    // Map.collect - the sandboxed script-security whitelist rejects a closure
    // resolving a static field lookup by key (confirmed by real test execution:
    // "RejectedAccessException: ... DefaultGroovyMethods invokeMethod").
    private static String toShArgs(Map<String, String> args) {
        List<String> parts = []
        if (args.installDir) { parts << "--install-dir ${shQuote(args.installDir)}" }
        if (args.version) { parts << "--version ${shQuote(args.version)}" }
        if (args.channel) { parts << "--channel ${shQuote(args.channel)}" }
        if (args.globalJson) { parts << "--global-json ${shQuote(args.globalJson)}" }
        if (args.defaultVersion) { parts << "--default-version ${shQuote(args.defaultVersion)}" }
        return parts.join(' ')
    }

    private static String toPsArgs(Map<String, String> args) {
        List<String> parts = []
        if (args.installDir) { parts << "-InstallDir ${psQuote(args.installDir)}" }
        if (args.version) { parts << "-Version ${psQuote(args.version)}" }
        if (args.channel) { parts << "-Channel ${psQuote(args.channel)}" }
        if (args.globalJson) { parts << "-GlobalJson ${psQuote(args.globalJson)}" }
        if (args.defaultVersion) { parts << "-DefaultVersion ${psQuote(args.defaultVersion)}" }
        return parts.join(' ')
    }

    // Pure string formatting, no Jenkins step calls - safe and unnecessary to
    // run through CPS transformation.
    @NonCPS
    private static String shQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    @NonCPS
    private static String psQuote(String value) {
        return "'" + value.replace("'", "''") + "'"
    }

    /**
     * Installs the selected SDK into the shared cache directory by running
     * the vendored install wrapper for the current OS, passing the install
     * dir and selector as CLI arguments (visible in the Jenkins console log)
     * rather than environment variables.
     */
    def install() {
        if (isUnix()) {
            jenkins.writeFile file: '.ci/dotnet-install.sh', text: jenkins.libraryResource('dotnet/dotnet-install.sh')
            jenkins.sh "chmod +x .ci/dotnet-install.sh && .ci/dotnet-install.sh ${toShArgs(installArgs())}"
        } else {
            jenkins.writeFile file: '.ci/dotnet-install.ps1', text: jenkins.libraryResource('dotnet/dotnet-install.ps1')
            jenkins.powershell ".ci\\dotnet-install.ps1 ${toPsArgs(installArgs())}"
        }
    }

    /**
     * Env entries exposing the installed SDK to a block or command: the
     * cache dir set as DOTNET_ROOT and prepended to PATH, DOTNET_BIN pointing
     * at the dotnet executable itself (dotnet on unix, dotnet.exe on
     * Windows) for callers that need the binary path rather than relying on
     * PATH resolution, plus NUGET_PACKAGES and DOTNET_CLI_HOME
     * unconditionally redirected under the shared cache tree (see
     * nugetHomeEnv()) - applied for every call this class makes, not just
     * tool-related ones, so nothing done through this class (or a caller's
     * block) can ever write to the user's default ~/.nuget or ~/.dotnet
     * locations.
     */
    List<String> withEnvList() {
        def dir = cacheDir()
        def pathSeparator = isUnix() ? ':' : ';'
        def dotnetBin = isUnix() ? "${dir}/dotnet" : "${dir}\\dotnet.exe"
        return ["DOTNET_ROOT=${dir}", "PATH=${dir}${pathSeparator}${jenkins.env.PATH}", "DOTNET_BIN=${dotnetBin}"] + nugetHomeEnv()
    }

    /**
     * Installs the SDK, then runs the given block with it available on PATH.
     * If a NuGet source was given, ensures it's registered (idempotent); if
     * credentials were also given, binds them for the block's duration and
     * exports NuGetPackageSourceCredentials_<source>. With no NuGet config at
     * all, this is purely an SDK install - NuGet is never touched.
     */
    def withInstalledDotnet(Closure block) {
        install()
        if (nugetCredentialsId) {
            jenkins.withCredentials([jenkins.usernamePassword(
                    credentialsId: nugetCredentialsId, usernameVariable: 'JFROG_USER', passwordVariable: 'JFROG_PASS')]) {
                jenkins.withEnv(withEnvList() + [nugetCredentialsEnv()]) {
                    ensureNuGetSource()
                    block()
                }
            }
        } else if (nugetSourceName) {
            jenkins.withEnv(withEnvList()) {
                ensureNuGetSource()
                block()
            }
        } else {
            jenkins.withEnv(withEnvList()) {
                block()
            }
        }
    }

    private String nugetCredentialsEnv() {
        return "NuGetPackageSourceCredentials_${nugetSourceName}=Username=${jenkins.env.JFROG_USER};Password=${jenkins.env.JFROG_PASS}"
    }

    /**
     * Registers the configured NuGet feed with the dotnet CLI if it isn't
     * already present, into a local, workspace-relative `./nuget.config`
     * (created via `dotnet new nugetconfig` if it doesn't already exist)
     * rather than the user-level NuGet.Config under DOTNET_CLI_HOME. This
     * guarantees the registration actually takes effect for this workspace:
     * a *project-level* nuget.config takes precedence over (and can fully
     * override, via `<clear />`) any user-level config, so relying solely on
     * the DOTNET_CLI_HOME-scoped user config risked the registration being
     * silently invisible to `dotnet` if such a project-level config already
     * existed or got created some other way. Idempotent and safe to call on
     * every invocation - a no-op once the source is present, whether that's
     * from a previous run in a persistent workspace or from this one.
     *
     * Deliberately does NOT pass credentials here (no --username/--password/
     * --store-password-in-clear-text): storing a password in a file - even
     * workspace-local - has a much larger blast radius than the env-var-based
     * NuGetPackageSourceCredentials_<source> approach already used elsewhere
     * in this class (see nugetCredentialsEnv()/withInstalledDotnet()), which
     * NuGet reads directly from the process environment regardless of which
     * NuGet.Config file registered the source name. That mechanism needs no
     * changes here.
     *
     * The leading guard clause and the echo/Write-Host that follows it are
     * permanent, not diagnostic scaffolding: DOTNET_CLI_HOME is always
     * expected to be set by the time this runs (withEnvList() guarantees it
     * for every call site in this class), so the guard fails loudly instead
     * of silently falling back to the user's default ~/.nuget location if
     * that invariant is ever broken by a future change, and the log line
     * records which DOTNET_CLI_HOME scope was used - useful when diagnosing
     * "package not found on my private feed" reports.
     *
     * label: is set to the actual `dotnet nuget add source` command (reusing
     * the same string used in the script body, not a hand-written paraphrase,
     * so the two can't drift apart) so the Jenkins UI's collapsed step
     * summary shows the meaningful command instead of the guard clause -
     * without it, Jenkins' fixed-width summary line shows the guard clause
     * and truncates the real command off the end entirely.
     *
     * On unix, the check-then-create-then-add sequence below is wrapped in a
     * workspace-relative lock (LockDir on nugetConfigLockDir()) so concurrent
     * invocations sharing a workspace can't race `dotnet new nugetconfig`
     * (confirmed by real execution: exit code 73, refuses to overwrite an
     * existing file). Windows is not covered here.
     */
    private void ensureNuGetSource() {
        if (isUnix()) {
            def addSourceCommand = "dotnet nuget add source \"${nugetSourceUrl}\" --name \"${nugetSourceName}\" --configfile ./nuget.config"
            new LockDir(jenkins, nugetConfigLockDir(), "NuGet config").withLock {
                jenkins.sh(label: addSourceCommand, script: ensureNuGetSourceScriptSh(addSourceCommand))
            }
        } else {
            def addSourceCommand = "dotnet nuget add source '${nugetSourceUrl}' --name '${nugetSourceName}' --configfile ./nuget.config"
            jenkins.powershell(
                    label: addSourceCommand,
                    script: "${requireDotnetCliHomePs()}; Write-Host \"[dotnet] Ensuring NuGet source '${nugetSourceName}' is registered in ./nuget.config (DOTNET_CLI_HOME='\$env:DOTNET_CLI_HOME')\"; if (-not ((dotnet nuget list source --format Short 2>\$null) | Select-String -SimpleMatch '${nugetSourceUrl}')) { if (-not (Test-Path ./nuget.config)) { dotnet new nugetconfig }; ${addSourceCommand} }")
        }
    }

    private String ensureNuGetSourceScriptSh(String addSourceCommand) {
        return [
                requireDotnetCliHomeSh(),
                "echo \"[dotnet] Ensuring NuGet source '${nugetSourceName}' is registered in ./nuget.config (DOTNET_CLI_HOME='\$DOTNET_CLI_HOME')\" >&2",
                "dotnet nuget list source --format Short 2>/dev/null | grep -qF \"${nugetSourceUrl}\" || { [ -f ./nuget.config ] || dotnet new nugetconfig; ${addSourceCommand}; }",
        ].join("\n")
    }

    // Workspace-relative, sibling to the ./nuget.config it protects.
    private String nugetConfigLockDir() {
        return "./nuget.config.lock"
    }

    /**
     * Cache directory for local dotnet tool packages and CLI resolver state,
     * kept under the same managed cache tree as the SDK itself rather than
     * the default ~/.nuget/packages / ~/.dotnet locations. Despite the name
     * (kept for API stability), this is now the single DOTNET_CLI_HOME/
     * NUGET_PACKAGES redirect target for every dotnet invocation this class
     * makes, not just tool-related ones - see nugetHomeEnv().
     */
    String toolCacheDir() {
        return isUnix() ? "${cacheDir()}/tools" : "${cacheDir()}\\tools"
    }

    /**
     * NUGET_PACKAGES/DOTNET_CLI_HOME, unconditionally redirected under
     * toolCacheDir(). Folded into withEnvList() so every withInstalledDotnet()
     * call (SDK-only or tool-related) applies the same redirect - there is
     * deliberately only ever one DOTNET_CLI_HOME scope per instance, which is
     * also why ensureNuGetSource() only needs to run once per
     * withInstalledDotnet() call: everything downstream (a caller's block,
     * installTool(), runTool()) shares that same scope, so a source
     * registered there is guaranteed visible to all of them.
     */
    private List<String> nugetHomeEnv() {
        def dir = toolCacheDir()
        def sep = isUnix() ? '/' : '\\'
        return ["NUGET_PACKAGES=${dir}${sep}packages", "DOTNET_CLI_HOME=${dir}"]
    }

    // Every dotnet invocation that could touch a NuGet.Config or the tool
    // resolver cache must run with DOTNET_CLI_HOME pointed at our managed
    // cache dir - withEnvList() always sets it (see nugetHomeEnv()), so it
    // should never be unset in practice. These guards are a defensive
    // backstop: if that invariant is ever broken by a future change, fail
    // loudly instead of silently writing to the user's real ~/.nuget or
    // ~/.dotnet. Three variants since this class shells out via sh (unix),
    // powershell (unix/windows install + NuGet source registration), and bat
    // (windows tool install/run) - each needs its own native syntax.
    @NonCPS
    private static String requireDotnetCliHomeSh() {
        return '[ -n "$DOTNET_CLI_HOME" ] || { echo "[dotnet] DOTNET_CLI_HOME is not set - refusing to run to avoid writing to the default NuGet/dotnet locations" >&2; exit 1; }'
    }

    @NonCPS
    private static String requireDotnetCliHomePs() {
        return 'if (-not $env:DOTNET_CLI_HOME) { Write-Error "[dotnet] DOTNET_CLI_HOME is not set - refusing to run to avoid writing to the default NuGet/dotnet locations"; exit 1 }'
    }

    @NonCPS
    private static String requireDotnetCliHomeBat() {
        return 'if not defined DOTNET_CLI_HOME (echo [dotnet] DOTNET_CLI_HOME is not set - refusing to run to avoid writing to the default NuGet/dotnet locations 1>&2 & exit /b 1)'
    }

    /**
     * Installs the SDK/NuGet feed (as withInstalledDotnet), ensures
     * <packageId> (optionally pinned to <version>) is installed as a local
     * (manifest-based) dotnet tool in the current workspace - creating a
     * tool manifest if one doesn't exist - then runs the given block with it
     * invocable via `dotnet tool run <toolBinary>` (or `dotnet <toolBinary>`).
     * NUGET_PACKAGES/DOTNET_CLI_HOME are already redirected by
     * withInstalledDotnet()'s withEnvList(), so no separate env scope or
     * NuGet source re-registration is needed here.
     */
    def withTool(String packageId, String version = null, Closure block) {
        withInstalledDotnet {
            installTool(packageId, version)
            block()
        }
    }

    /**
     * Installs the SDK/NuGet feed/tool (as withTool), then runs <toolBinary>
     * via `dotnet tool run` with the given args.
     *
     * `options` groups the optional unix-only knobs, which had grown past
     * readable as trailing positional parameters. It must be passed as an
     * explicit Map literal (`runTool(p, b, a, v, false, [stderrFile: "x.log"])`):
     * Groovy's bare named-argument sugar (`..., stderrFile: "x.log"`, no
     * brackets) always collapses into a Map passed as the call's *first*
     * argument, never reaching a trailing Map parameter - it throws
     * MissingMethodException instead (confirmed by real execution).
     *
     * - loginShell/umask/logCommandToStdErr only apply on unix (bat has no
     *   shebang, umask, or `set -x` equivalent) and are ignored on Windows:
     *   - loginShell: run via a `#!/bin/bash -l` shebang instead of Jenkins'
     *     default `sh -xe`, so the tool sees the same environment a login shell
     *     would set up (e.g. profile-sourced PATH entries). A login shell
     *     re-sources /etc/profile and ~/.bash_profile, which on some agents
     *     unconditionally overwrites PATH, discarding the cache dir
     *     withInstalledDotnet put on it - DOTNET_ROOT survives this (it's a
     *     plain Jenkins-set env var, not something profile scripts touch), so
     *     an `export PATH="$DOTNET_ROOT:$PATH"` is automatically re-added right
     *     after the shebang to defend against that (same fix documented as a
     *     manual caveat for withDotnet). This also replaces Jenkins' default
     *     invocation entirely, including its default `-x` tracing - use
     *     logCommandToStdErr to opt back into that explicitly.
     *   - umask: prepended as `umask <value>` before the command, matching the
     *     umask convention used elsewhere in this library for shared-cache-safe
     *     file permissions.
     *   - logCommandToStdErr: prepends `set -x` (echoes each command to stderr
     *     before running it), since a custom loginShell shebang above loses
     *     Jenkins' own default `-xe` tracing.
     * - stdoutFile/stderrFile: unix/macOS only - see runToolCapturingOutput().
     *   Either or both may be given; each names a workspace-relative file the
     *   corresponding stream is duplicated into while still streaming live to
     *   the console. The caller owns those files: the step's own return/throw
     *   contract is untouched (a nonzero tool exit still throws unless
     *   returnStatus is set), and the output is read back out of band with
     *   readFile - including from a post block, which is the point. Note this
     *   forces a bash shebang internally regardless of
     *   loginShell/logCommandToStdErr, so Jenkins' default `-x` tracing is
     *   dropped even when loginShell isn't set - pass logCommandToStdErr too
     *   if that tracing is wanted back.
     */
    def runTool(String packageId, String toolBinary, List<String> args, String version, Boolean returnStatus, Map options = [:]) {
        Boolean loginShell = (options.loginShell ?: false) as Boolean
        String umask = options.umask as String
        Boolean logCommandToStdErr = (options.logCommandToStdErr ?: false) as Boolean
        String stdoutFile = options.stdoutFile as String
        String stderrFile = options.stderrFile as String

        validateCaptureFiles(stdoutFile, stderrFile, isUnix())
        return withTool(packageId, version) {
            // The "--" separator is required: without it, dotnet's own CLI parser
            // intercepts args that look like its own options (e.g. --help, -h) before
            // they ever reach the tool, printing `dotnet tool run`'s help instead of
            // forwarding the flag (confirmed by real execution).
            def command = (["dotnet", "tool", "run", toolBinary, "--"] + args).join(" ")
            if (stdoutFile || stderrFile) {
                return runToolCapturingOutput(command, stdoutFile, stderrFile, loginShell, umask, logCommandToStdErr, returnStatus)
            }
            // label: reuses the same command string shown in the script body
            // (not a hand-written paraphrase) so the Jenkins UI's collapsed
            // step summary shows the meaningful command instead of the guard
            // clause, which would otherwise dominate the fixed-width summary
            // line and truncate the real command off the end.
            if (isUnix()) {
                return jenkins.sh(label: command, script: shScript(command, loginShell, umask, logCommandToStdErr), returnStatus: returnStatus)
            } else {
                return jenkins.bat(label: command, script: "${requireDotnetCliHomeBat()} & ${command}", returnStatus: returnStatus)
            }
        }
    }

    // Windows is rejected rather than ignored (unlike loginShell/umask/
    // logCommandToStdErr): those only change how the command is invoked, while
    // silently not producing a file the caller explicitly named - and will go on
    // to readFile - would surface far from the mistake, as a missing-file error
    // in whatever post block reads it (or worse, as a stale file from an earlier
    // build read back as this run's output).
    @NonCPS
    private static void validateCaptureFiles(String stdoutFile, String stderrFile, boolean unix) {
        if (!stdoutFile && !stderrFile) {
            return
        }
        if (!unix) {
            throw new IllegalArgumentException(
                    "runTool: 'stdoutFile'/'stderrFile' are only supported on unix/macOS agents")
        }
        validateCaptureFile("stdoutFile", stdoutFile)
        validateCaptureFile("stderrFile", stderrFile)
        // Both streams teeing into one file would interleave them unpredictably,
        // and both FIFOs would collide on the same `<file>.fifo` path - so the
        // capture would be quietly wrong rather than merely combined.
        if (stdoutFile && stdoutFile == stderrFile) {
            throw new IllegalArgumentException(
                    "runTool: 'stdoutFile' and 'stderrFile' must name different files - use one of them alone to capture a single stream")
        }
    }

    // Validated, never sanitized: the caller named this exact path and will read
    // it back itself, so quietly writing somewhere else is worse than refusing.
    // The characters below would expand or break out inside the generated
    // script's double-quoted paths; workspace-relative is required because
    // readFile()/fileExists() resolve against the workspace, so an absolute path
    // would be written but never readable by the caller.
    @NonCPS
    private static void validateCaptureFile(String name, String path) {
        if (path == null) {
            return
        }
        if (path.trim().isEmpty()) {
            throw new IllegalArgumentException("runTool: '${name}' must not be blank")
        }
        String forbidden = ['"', '$', '`', '\\', '\n', '\r'].find { path.contains(it) }
        if (forbidden) {
            throw new IllegalArgumentException(
                    "runTool: '${name}' must not contain quotes, \$, backticks, backslashes, or newlines - got '${path}'")
        }
        if (path.startsWith("/")) {
            throw new IllegalArgumentException(
                    "runTool: '${name}' must be workspace-relative, not absolute - got '${path}'")
        }
        if (path.tokenize("/").contains("..")) {
            throw new IllegalArgumentException(
                    "runTool: '${name}' must not escape the workspace with '..' - got '${path}'")
        }
    }

    // The generated script does the duplicating itself, so sh() keeps its normal
    // contract: the caller's returnStatus choice is passed straight through and a
    // nonzero tool exit throws exactly as it does without capture. The captured
    // text reaches the caller out of band, via its own readFile() on the files it
    // named - which is what lets a post block read output from a stage whose tool
    // failed. Only the FIFOs are cleaned up here; the capture files belong to the
    // caller and deliberately outlive this call.
    private def runToolCapturingOutput(String command, String stdoutFile, String stderrFile, Boolean loginShell,
                                       String umask, Boolean logCommandToStdErr, Boolean returnStatus) {
        String quotedFifos = [stdoutFile, stderrFile].findAll { it }.collect { "\"${it}.fifo\"" }.join(" ")
        try {
            return jenkins.sh(
                    label: command,
                    script: captureOutputScriptSh(command, stdoutFile, stderrFile, loginShell, umask, logCommandToStdErr),
                    returnStatus: returnStatus)
        } finally {
            try {
                // The script's own trailing `rm -f` only runs on a clean exit -
                // an abort or kill skips it - so this is the only FIFO cleanup
                // guaranteed to run however the script terminated.
                jenkins.sh(script: "rm -f ${quotedFifos}", returnStatus: true)
            } catch (Exception ignored) {
                // A cleanup failure must never mask an exception already
                // propagating from the try block (e.g. the tool's own nonzero
                // exit, or an agent disconnect) - a stray FIFO is the far
                // smaller problem.
            }
        }
    }

    // A custom shebang must be the very first line of the script for Jenkins'
    // sh step to honour it. The PATH re-export comes right after it, before
    // logCommandToStdErr/umask, since a login shell's profile-sourcing may
    // have already clobbered PATH by the time the script body starts running.
    // The DOTNET_CLI_HOME guard comes last, immediately before the actual
    // command, since it's a precondition check for that command specifically -
    // which is why preGuardLines exists: captureOutputScriptSh() needs its file
    // truncation to run even when that guard exits (see there), while still
    // landing after `umask` so the files get the caller's intended permissions.
    // Shared between shScript() and captureOutputScriptSh() (via forceBash) so
    // any future preamble addition applies to both automatically instead of
    // risking one variant getting updated and the other silently left behind.
    private static List<String> scriptPreambleLines(Boolean loginShell, String umask, Boolean logCommandToStdErr,
                                                   Boolean forceBash = false, List<String> preGuardLines = []) {
        List<String> lines = []
        if (loginShell) {
            lines << "#!/bin/bash -l"
            lines << 'export PATH="$DOTNET_ROOT:$PATH"'
        } else if (forceBash) {
            lines << "#!/bin/bash"
        }
        if (logCommandToStdErr) { lines << "set -x" }
        if (umask) { lines << "umask ${umask}" }
        lines.addAll(preGuardLines)
        lines << requireDotnetCliHomeSh()
        return lines
    }

    private static String shScript(String command, Boolean loginShell, String umask, Boolean logCommandToStdErr) {
        return (scriptPreambleLines(loginShell, umask, logCommandToStdErr) + [command]).join("\n")
    }

    // Duplicates each stream to its own capture file while still passing it
    // through to the live console, rather than smuggling redirection through the
    // caller-supplied args list (which would rely on the current accidental
    // unquoted arg-joining). Every non-obvious choice below was confirmed by
    // real execution on bash 3.2 and 5.3, not just reasoning; the full decision
    // history is in openspec/changes/capture-dotnet-tool-output/design.md.
    //
    // - `tee` reads from named FIFOs as real background jobs, not `> >(tee ...)`:
    //   process-substitution subshells never enter the job table, so `wait`
    //   returns without them having flushed. A plain `>`/`2>` redirect is wrong
    //   differently - it diverts output away from the console-connected fds
    //   Jenkins watches, so the run would go silent until it finished.
    // - `exec 3>&1 4>&2` saves the console fds before anything reassigns
    //   fd1/fd2, and each tee targets its saved fd explicitly - otherwise the
    //   second tee's passthrough can inherit the first redirection's target,
    //   cross-contaminating the capture files.
    // - The `rm -f` before `mkfifo` clears leftovers from a crashed/killed run:
    //   a stale *regular* file at a FIFO path makes tee hit EOF instantly and
    //   capture nothing, with an unremarkable exit code. `|| exit 125` makes a
    //   genuine mkfifo failure (disk full, permissions) loud instead of letting
    //   it cascade into the same silent shape - 125 is a recognizable
    //   setup-failed sentinel, distinct from plausible tool exit codes.
    // - <command> redirects straight into the FIFOs (no pipe), so `$?` is its
    //   own exit status - captured into _exit_code before the fd-closes and
    //   `wait` can clobber it, and restored by the final `exit`. It also closes
    //   fds 3/4 (`3>&- 4>&-`) so neither it nor any child it spawns keeps a
    //   handle to the console descriptors, which a lingering child could
    //   otherwise hold open against the Durable Task step.
    // - The forced shebang is load-bearing, not stylistic: without one, Jenkins
    //   runs this under `sh -e`, which kills the script the moment <command>
    //   exits non-zero - before _exit_code, wait, or cleanup - and leaks the
    //   FIFOs. Any shebang escapes `-e` (and makes the explicit `exit 125`
    //   necessary); bash specifically is just consistency with loginShell.
    // - The watchdog bounds `wait`: a child that outlives <command> while
    //   holding a FIFO's write end open means that tee never sees EOF and
    //   `wait` blocks forever - killing the tees after the timeout degrades to
    //   truncated capture instead of a hung step. On the normal path,
    //   `pkill -P` must take the watchdog's `sleep` before `kill` takes the
    //   subshell, or the sleep is orphaned for its full timeout. `pkill -P`
    //   isn't POSIX but is present on both Linux and macOS agents.
    // - The leading `: >` truncation is what makes the caller-owned files safe to
    //   read unconditionally: the workspace is reused across builds, so without
    //   it a script that exits before <command> ever runs (the DOTNET_CLI_HOME
    //   guard, or `exit 125`) would leave an *earlier* build's file in place for
    //   the caller to read back as this run's output. It therefore has to precede
    //   that guard, hence preGuardLines - see scriptPreambleLines().
    // - Only the requested streams get a FIFO and a tee; an unrequested stream is
    //   left entirely alone and reaches the console untouched, so capturing just
    //   stderr costs nothing on stdout.
    private static String captureOutputScriptSh(String command, String stdoutFile, String stderrFile,
                                                 Boolean loginShell, String umask, Boolean logCommandToStdErr) {
        List<String> captureFiles = [stdoutFile, stderrFile].findAll { it }
        String quotedFifos = captureFiles.collect { "\"${it}.fifo\"" }.join(" ")
        List<String> lines = scriptPreambleLines(loginShell, umask, logCommandToStdErr, true,
                captureFiles.collect { ": > \"${it}\"" })
        lines << 'exec 3>&1 4>&2'
        lines << "rm -f ${quotedFifos}"
        lines << "mkfifo ${quotedFifos} || exit 125"
        List<String> teePids = []
        if (stdoutFile) {
            lines << "tee \"${stdoutFile}\" >&3 < \"${stdoutFile}.fifo\" &"
            lines << '_stdout_tee_pid=$!'
            teePids << '"$_stdout_tee_pid"'
        }
        if (stderrFile) {
            lines << "tee \"${stderrFile}\" >&4 < \"${stderrFile}.fifo\" &"
            lines << '_stderr_tee_pid=$!'
            teePids << '"$_stderr_tee_pid"'
        }
        String redirects = (stdoutFile ? " > \"${stdoutFile}.fifo\"" : "") + (stderrFile ? " 2> \"${stderrFile}.fifo\"" : "")
        lines << "${command}${redirects} 3>&- 4>&-"
        lines << '_exit_code=$?'
        lines << 'exec 3>&- 4>&-'
        lines << "( sleep ${CAPTURE_OUTPUT_WATCHDOG_TIMEOUT_SECONDS}; kill ${teePids.join(" ")} 2>/dev/null ) &"
        lines << '_watchdog_pid=$!'
        lines << "wait ${teePids.join(" ")}"
        lines << 'pkill -P "$_watchdog_pid" 2>/dev/null; kill "$_watchdog_pid" 2>/dev/null'
        lines << "rm -f ${quotedFifos}"
        lines << 'exit $_exit_code'
        return lines.join("\n")
    }

    // dotnet tool install is idempotent on its own for a local/manifest tool:
    // re-installing the same version is a no-op ("up to date"), and a version
    // change requires --allow-downgrade regardless of direction (confirmed by
    // real execution) - no extra existence check needed here, unlike the
    // nuget source registration above.
    private void installTool(String packageId, String version) {
        def versionArgs = version ? " --version ${version} --allow-downgrade" : ""
        def command = "dotnet tool install ${packageId} --create-manifest-if-needed${versionArgs}"
        // label: reuses the command string itself (see runTool()) rather than
        // a paraphrase, for the same Jenkins-UI-summary reason.
        if (isUnix()) {
            // Concurrent runs on one agent share NUGET_PACKAGES/DOTNET_CLI_HOME
            // (see nugetHomeEnv()), so simultaneous `dotnet tool install`s can
            // race restoring the same package into that shared packages folder
            // (confirmed by real execution: "The process cannot access the
            // file '....nupkg' because it is being used by another process").
            // The lock is agent-wide (one dir, not per package/version), since
            // NUGET_PACKAGES also holds shared transitive-dependency packages
            // different tools could race on. Windows (bat) is not covered.
            new LockDir(jenkins, toolInstallLockDir(), "tool install").withLock {
                jenkins.sh(label: command, script: toolInstallScriptSh(command))
            }
        } else {
            jenkins.bat(label: command, script: "${requireDotnetCliHomeBat()} & ${command}")
        }
    }

    // Agent-wide, a sibling of the shared tool cache it protects.
    private String toolInstallLockDir() {
        return "${toolCacheDir()}.tool-install.lock"
    }

    // TMPDIR is echoed as a diagnostic (not asserted on in tests) - dotnet's
    // own package-extraction locking is TMPDIR-scoped, so seeing whether
    // TMPDIR is workspace-scoped or shared agent-wide here explains why that
    // locking alone didn't prevent the race the surrounding lock now guards
    // against.
    private String toolInstallScriptSh(String command) {
        return [
                requireDotnetCliHomeSh(),
                'echo "[dotnet] TMPDIR=\'$TMPDIR\' WORKSPACE=\'$WORKSPACE\'" >&2',
                command,
        ].join("\n")
    }
}
