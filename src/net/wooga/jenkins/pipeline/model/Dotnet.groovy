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
     * Provisions the SDK, then runs the given block with it available on PATH.
     */
    def withProvisionedEnv(Closure block) {
        provision()
        jenkins.withEnv(withEnvList()) {
            block()
        }
    }
}
