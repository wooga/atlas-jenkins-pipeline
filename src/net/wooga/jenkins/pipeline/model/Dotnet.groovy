package net.wooga.jenkins.pipeline.model

import com.cloudbees.groovy.cps.NonCPS

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
     * point (cacheDir/install/withEnvList/toolCacheDir/toolEnv/ensureNuGetSource/
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
     * cache dir set as DOTNET_ROOT and prepended to PATH.
     */
    List<String> withEnvList() {
        def dir = cacheDir()
        def pathSeparator = isUnix() ? ':' : ';'
        return ["DOTNET_ROOT=${dir}", "PATH=${dir}${pathSeparator}${jenkins.env.PATH}"]
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
     * already present. Idempotent and safe to call on every invocation: this
     * writes to the user-level NuGet.Config, so on a persistent agent it's a
     * no-op after the first run.
     */
    private void ensureNuGetSource() {
        if (isUnix()) {
            jenkins.sh "dotnet nuget list source --format Short 2>/dev/null | grep -qF \"${nugetSourceUrl}\" || dotnet nuget add source \"${nugetSourceUrl}\" --name \"${nugetSourceName}\""
        } else {
            jenkins.powershell "if (-not ((dotnet nuget list source --format Short 2>\$null) | Select-String -SimpleMatch '${nugetSourceUrl}')) { dotnet nuget add source '${nugetSourceUrl}' --name '${nugetSourceName}' }"
        }
    }

    /**
     * Cache directory for local dotnet tool packages and CLI resolver state,
     * kept under the same managed cache tree as the SDK itself rather than
     * the default ~/.nuget/packages / ~/.dotnet locations.
     */
    String toolCacheDir() {
        return isUnix() ? "${cacheDir()}/tools" : "${cacheDir()}\\tools"
    }

    private List<String> toolEnv() {
        def dir = toolCacheDir()
        def sep = isUnix() ? '/' : '\\'
        return ["NUGET_PACKAGES=${dir}${sep}packages", "DOTNET_CLI_HOME=${dir}"]
    }

    /**
     * Installs the SDK/NuGet feed (as withInstalledDotnet), ensures
     * <packageId> (optionally pinned to <version>) is installed as a local
     * (manifest-based) dotnet tool in the current workspace - creating a
     * tool manifest if one doesn't exist - then runs the given block with it
     * invocable via `dotnet tool run <toolBinary>` (or `dotnet <toolBinary>`).
     * The tool's package cache is redirected under toolCacheDir() for the
     * block's duration, not the default ~/.nuget or ~/.dotnet locations.
     */
    def withTool(String packageId, String version = null, Closure block) {
        withInstalledDotnet {
            jenkins.withEnv(toolEnv()) {
                // DOTNET_CLI_HOME above redirects dotnet's user-level
                // NuGet.Config to a location separate from the one
                // withInstalledDotnet() already registered the feed under
                // (confirmed by real execution: DOTNET_CLI_HOME genuinely
                // changes which NuGet.Config dotnet nuget/dotnet tool
                // reads and writes - a source registered under one value
                // is invisible under another). Without re-registering here,
                // `dotnet tool install` only ever sees the default nuget.org
                // feed and fails to find a private package. ensureNuGetSource
                // is idempotent, so calling it again in this scope is safe.
                if (nugetSourceName) {
                    ensureNuGetSource()
                }
                installTool(packageId, version)
                block()
            }
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
            if (isUnix()) {
                return jenkins.sh(script: shScript(command, loginShell, umask, logCommandToStdErr), returnStatus: returnStatus)
            } else {
                return jenkins.bat(script: command, returnStatus: returnStatus)
            }
        }
    }

    // A custom shebang must be the very first line of the script for Jenkins'
    // sh step to honour it. The PATH re-export comes right after it, before
    // logCommandToStdErr/umask, since a login shell's profile-sourcing may
    // have already clobbered PATH by the time the script body starts running.
    private static String shScript(String command, Boolean loginShell, String umask, Boolean logCommandToStdErr) {
        List<String> lines = []
        if (loginShell) {
            lines << "#!/bin/bash -l"
            lines << 'export PATH="$DOTNET_ROOT:$PATH"'
        }
        if (logCommandToStdErr) { lines << "set -x" }
        if (umask) { lines << "umask ${umask}" }
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
        if (isUnix()) {
            jenkins.sh command
        } else {
            jenkins.bat command
        }
    }
}
