package net.wooga.jenkins.pipeline.model

import com.cloudbees.groovy.cps.NonCPS
import net.wooga.jenkins.pipeline.cache.Lockfile

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
     * workspace-relative lock (Lockfile on nugetConfigLockDir()) so concurrent
     * invocations sharing a workspace can't race `dotnet new nugetconfig`
     * (confirmed by real execution: exit code 73, refuses to overwrite an
     * existing file). Windows is not covered here.
     */
    private void ensureNuGetSource() {
        if (isUnix()) {
            def addSourceCommand = "dotnet nuget add source \"${nugetSourceUrl}\" --name \"${nugetSourceName}\" --configfile ./nuget.config"
            new Lockfile(jenkins, nugetConfigLockDir(), "NuGet config").withLock(
                    mode: Lockfile.BREAK_STALE, timeoutSeconds: nugetConfigLockTimeoutSeconds()) {
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

    // Stale-lock timeout for the NuGet config lock, overridable via env.
    private int nugetConfigLockTimeoutSeconds() {
        return (jenkins.env.DOTNET_NUGET_CONFIG_LOCK_TIMEOUT ?: 300) as int
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
     * loginShell/umask/logCommandToStdErr only apply on unix (bat has no
     * shebang, umask, or `set -x` equivalent) and are ignored on Windows:
     * - loginShell: run via a `#!/bin/bash -l` shebang instead of Jenkins'
     *   default `sh -xe`, so the tool sees the same environment a login shell
     *   would set up (e.g. profile-sourced PATH entries). A login shell
     *   re-sources /etc/profile and ~/.bash_profile, which on some agents
     *   unconditionally overwrites PATH, discarding the cache dir
     *   withInstalledDotnet put on it - DOTNET_ROOT survives this (it's a
     *   plain Jenkins-set env var, not something profile scripts touch), so
     *   an `export PATH="$DOTNET_ROOT:$PATH"` is automatically re-added right
     *   after the shebang to defend against that (same fix documented as a
     *   manual caveat for withDotnet). This also replaces Jenkins' default
     *   invocation entirely, including its default `-x` tracing - use
     *   logCommandToStdErr to opt back into that explicitly.
     * - umask: prepended as `umask <value>` before the command, matching the
     *   umask convention used elsewhere in this library for shared-cache-safe
     *   file permissions.
     * - logCommandToStdErr: prepends `set -x` (echoes each command to stderr
     *   before running it), since a custom loginShell shebang above loses
     *   Jenkins' own default `-xe` tracing.
     */
    def runTool(String packageId, String toolBinary, List<String> args, String version, Boolean returnStatus,
                Boolean loginShell = false, String umask = null, Boolean logCommandToStdErr = false) {
        return withTool(packageId, version) {
            // The "--" separator is required: without it, dotnet's own CLI parser
            // intercepts args that look like its own options (e.g. --help, -h) before
            // they ever reach the tool, printing `dotnet tool run`'s help instead of
            // forwarding the flag (confirmed by real execution).
            def command = (["dotnet", "tool", "run", toolBinary, "--"] + args).join(" ")
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

    // A custom shebang must be the very first line of the script for Jenkins'
    // sh step to honour it. The PATH re-export comes right after it, before
    // logCommandToStdErr/umask, since a login shell's profile-sourcing may
    // have already clobbered PATH by the time the script body starts running.
    // The DOTNET_CLI_HOME guard comes last, immediately before the actual
    // command, since it's a precondition check for that command specifically.
    private static String shScript(String command, Boolean loginShell, String umask, Boolean logCommandToStdErr) {
        List<String> lines = []
        if (loginShell) {
            lines << "#!/bin/bash -l"
            lines << 'export PATH="$DOTNET_ROOT:$PATH"'
        }
        if (logCommandToStdErr) { lines << "set -x" }
        if (umask) { lines << "umask ${umask}" }
        lines << requireDotnetCliHomeSh()
        lines << command
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
            new Lockfile(jenkins, toolInstallLockDir(), "tool install").withLock(
                    mode: Lockfile.BREAK_STALE, timeoutSeconds: toolInstallLockTimeoutSeconds()) {
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

    // Stale-lock timeout for the tool install lock, overridable via env.
    private int toolInstallLockTimeoutSeconds() {
        return (jenkins.env.DOTNET_TOOL_INSTALL_LOCK_TIMEOUT ?: 300) as int
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
