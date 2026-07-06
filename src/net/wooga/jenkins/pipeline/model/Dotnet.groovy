package net.wooga.jenkins.pipeline.model

import com.cloudbees.groovy.cps.NonCPS

/**
 * Provisions the .NET SDK into a shared per-agent cache directory (via the
 * vendored dotnet-install wrapper scripts) and exposes it to callers.
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
     * Company-wide private NuGet feed, registered idempotently (once per
     * agent, since `dotnet nuget add source` writes to the user-level
     * NuGet.Config) so every dotnetWrapper/withDotnet invocation can restore
     * from it without callers wiring this up themselves.
     */
    static final String NUGET_SOURCE_NAME = "wooga_nuget"
    static final String NUGET_SOURCE_URL = "https://wooga.jfrog.io/artifactory/api/nuget/v3/wooga_nuget/index.json"

    /**
     * Jenkins credentials ID for read access to the private NuGet feed above.
     * Matches the existing 'artifactory_read' convention already hardcoded by
     * other steps in this library (e.g. javaLibs.groovy, buildWDK.groovy).
     */
    static final String NUGET_CREDENTIALS_ID = "artifactory_read"

    private Object jenkins
    private String version
    private String channel
    private String globalJson

    static Dotnet fromJenkins(Object jenkinsScript, Map args = [:]) {
        return new Dotnet(jenkinsScript, args.version as String, args.channel as String, args.globalJson as String)
    }

    Dotnet(Object jenkins, String version = null, String channel = null, String globalJson = null) {
        validateSelectors(version, channel, globalJson)
        this.jenkins = jenkins
        this.version = version
        this.channel = channel
        this.globalJson = globalJson
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

    /**
     * The shared install/cache directory for the current agent's OS:
     * ~/.cache/dotnet on unix, %LOCALAPPDATA%\cache\dotnet on Windows.
     */
    String cacheDir() {
        if (jenkins.isUnix()) {
            return "${jenkins.env.HOME}/.cache/dotnet"
        }
        return "${jenkins.env.LOCALAPPDATA}\\cache\\dotnet"
    }

    private List<String> selectorEnv() {
        List<String> envVars = ["DOTNET_INSTALL_DIR=${cacheDir()}"]
        if (version) {
            envVars << "DOTNET_VERSION=${version}"
        } else if (channel) {
            envVars << "DOTNET_CHANNEL=${channel}"
        } else if (globalJson) {
            envVars << "GLOBAL_JSON=${globalJson}"
        } else if (jenkins.fileExists('global.json')) {
            // Let the wrapper script auto-detect the workspace global.json and
            // install its sdk.version's major.minor as a floating channel
            // (matches how GitHub Actions' setup-dotnet resolves global.json).
        } else {
            // Distinct from DOTNET_VERSION so the install log can tell an
            // explicit version argument apart from this org-wide fallback.
            envVars << "DOTNET_DEFAULT_VERSION=${DEFAULT_VERSION}"
        }
        return envVars
    }

    /**
     * Ensures the selected SDK is installed into the shared cache directory by
     * running the vendored install wrapper for the current OS.
     */
    def provision() {
        jenkins.withEnv(selectorEnv()) {
            if (jenkins.isUnix()) {
                jenkins.writeFile file: '.ci/dotnet-install.sh', text: jenkins.libraryResource('dotnet/dotnet-install.sh')
                jenkins.sh 'chmod +x .ci/dotnet-install.sh && .ci/dotnet-install.sh'
            } else {
                jenkins.writeFile file: '.ci/dotnet-install.ps1', text: jenkins.libraryResource('dotnet/dotnet-install.ps1')
                jenkins.powershell '.ci\\dotnet-install.ps1'
            }
        }
    }

    /**
     * Env entries exposing the provisioned SDK to a block or command: the
     * cache dir set as DOTNET_ROOT and prepended to PATH.
     */
    List<String> withEnvList() {
        def dir = cacheDir()
        def pathSeparator = jenkins.isUnix() ? ':' : ';'
        return ["DOTNET_ROOT=${dir}", "PATH=${dir}${pathSeparator}${jenkins.env.PATH}"]
    }

    /**
     * Provisions the SDK, ensures the shared wooga_nuget feed is registered
     * and authenticated, then runs the given block with it all available.
     */
    def withProvisionedEnv(Closure block) {
        provision()
        jenkins.withCredentials([jenkins.usernamePassword(
                credentialsId: NUGET_CREDENTIALS_ID, usernameVariable: 'JFROG_USER', passwordVariable: 'JFROG_PASS')]) {
            jenkins.withEnv(withEnvList() + [nugetCredentialsEnv()]) {
                ensureNuGetSource()
                block()
            }
        }
    }

    private String nugetCredentialsEnv() {
        return "NuGetPackageSourceCredentials_${NUGET_SOURCE_NAME}=Username=${jenkins.env.JFROG_USER};Password=${jenkins.env.JFROG_PASS}"
    }

    /**
     * Cache directory for local dotnet tool packages and CLI resolver state,
     * kept under the same managed cache tree as the SDK itself rather than
     * the default ~/.nuget/packages / ~/.dotnet locations.
     */
    String toolCacheDir() {
        return jenkins.isUnix() ? "${cacheDir()}/tools" : "${cacheDir()}\\tools"
    }

    private List<String> toolEnv() {
        def dir = toolCacheDir()
        def sep = jenkins.isUnix() ? '/' : '\\'
        return ["NUGET_PACKAGES=${dir}${sep}packages", "DOTNET_CLI_HOME=${dir}"]
    }

    /**
     * Provisions the SDK/creds/nuget feed, ensures <packageId> (optionally
     * pinned to <version>) is installed as a local (manifest-based) dotnet
     * tool in the current workspace - creating a tool manifest if one
     * doesn't exist - then runs the given block with it invocable via
     * `dotnet tool run <toolBinary>` (or `dotnet <toolBinary>`). The tool's
     * package cache is redirected under toolCacheDir() for the block's
     * duration, not the default ~/.nuget or ~/.dotnet locations.
     */
    def withTool(String packageId, String version = null, Closure block) {
        withProvisionedEnv {
            jenkins.withEnv(toolEnv()) {
                installTool(packageId, version)
                block()
            }
        }
    }

    /**
     * Provisions the SDK/creds/tool (as withTool), then runs <toolBinary>
     * via `dotnet tool run` with the given args.
     */
    def runTool(String packageId, String toolBinary, List<String> args, String version, Boolean returnStatus) {
        return withTool(packageId, version) {
            def command = (["dotnet", "tool", "run", toolBinary] + args).join(" ")
            if (jenkins.isUnix()) {
                return jenkins.sh(script: command, returnStatus: returnStatus)
            } else {
                return jenkins.bat(script: command, returnStatus: returnStatus)
            }
        }
    }

    // dotnet tool install is idempotent on its own for a local/manifest tool:
    // re-installing the same version is a no-op ("up to date"), and a version
    // change requires --allow-downgrade regardless of direction (confirmed by
    // real execution) - no extra existence check needed here, unlike the
    // nuget source registration above.
    private void installTool(String packageId, String version) {
        def versionArgs = version ? " --version ${version} --allow-downgrade" : ""
        def command = "dotnet tool install ${packageId} --create-manifest-if-needed${versionArgs}"
        if (jenkins.isUnix()) {
            jenkins.sh command
        } else {
            jenkins.bat command
        }
    }

    /**
     * Registers the shared wooga_nuget feed with the dotnet CLI if it isn't
     * already present. Idempotent and safe to call on every invocation: this
     * writes to the user-level NuGet.Config, so on a persistent agent it's a
     * no-op after the first run.
     */
    private void ensureNuGetSource() {
        if (jenkins.isUnix()) {
            jenkins.sh "dotnet nuget list source --format Short 2>/dev/null | grep -qF \"${NUGET_SOURCE_URL}\" || dotnet nuget add source \"${NUGET_SOURCE_URL}\" --name \"${NUGET_SOURCE_NAME}\""
        } else {
            jenkins.powershell "if (-not ((dotnet nuget list source --format Short 2>\$null) | Select-String -SimpleMatch '${NUGET_SOURCE_URL}')) { dotnet nuget add source '${NUGET_SOURCE_URL}' --name '${NUGET_SOURCE_NAME}' }"
        }
    }
}
