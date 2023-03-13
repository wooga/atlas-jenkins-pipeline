package scripts


import spock.lang.Unroll
import tools.DeclarativeJenkinsSpec

class BuildUnityWdkV2Spec extends DeclarativeJenkinsSpec {
    private static final String SCRIPT_PATH = "vars/buildUnityWdkV2.groovy"

    @Unroll("publishes UPM/Paket WDK #releaseType-#releaseScope release")
    def "publishes UPM/Paket WDK with #release release type"() {
        given: "credentials holder with publish keys"
        credentials.addUsernamePassword("artifactory_publish", "usr", "pwd")
        credentials.addUsernamePassword('github_access', "usr", "pwd")
        and: "build plugin with publish parameters"
        def buildWDK = loadSandboxedScript(SCRIPT_PATH) {
            params.RELEASE_STAGE = releaseType
            params.RELEASE_SCOPE = releaseScope
            env.GRGIT_USR = "usr"
            env.GRGIT_PSW = "pwd"
        }

        when: "running buildWDKAutoSwitch pipeline"
        inSandbox { buildWDK(unityVersions: ["2019"], logLevel: "level") }

        then: "runs gradle with parameters"
        assertShCallsWith("gradlew",
                "publish",
                "-Ppaket.publish.repository='${releaseType}'",
                "-Ppublish.repository='${releaseType}'",
                "-PversionBuilder.stage=${releaseType}",
                "-PversionBuilder.scope=${releaseScope}"
        )
        and: "has set up environment"
        def env = usedEnvironments[usedEnvironments.size()-2]
        hasBaseEnvironment(env, "level")
        env.GRGIT == this.credentials['github_access']
        env.GRGIT_USER == "usr" //"${GRGIT_USR}"
        env.GRGIT_PASS == "pwd" //"${GRGIT_PSW}"
        env.GITHUB_LOGIN == "usr" //"${GRGIT_USR}"
        env.GITHUB_PASSWORD == "pwd" //"${GRGIT_PSW}"
        env.UNITY_PACKAGE_MANAGER == "upm"
        env.UNITY_LOG_CATEGORY == "build"
        env.UPM_USERNAME == "usr" //artifactory_publish user
        env.UPM_PASSWORD == "pwd" //artifactory_publish password
        env.NUGET_KEY == this.credentials['artifactory_publish']

        where:
        releaseType | releaseScope
        "snapshot"  | "patch"
        "preflight" | "minor"
        "rc"        | "minor"
        "final"     | "major"
    }


    @Unroll("assemblies WDK for #releaseType-#releaseScope release ")
    def "assemblies UPM WDK with given release data"() {
        given: "needed credentials"
        def upmCredsFile = credentials.addFile("atlas-upm-credentials", "creds")
        credentials.addUsernamePassword("artifactory_read", "key", "pwd")
        and: "build plugin with publish parameters"
        def buildWDK = loadSandboxedScript(SCRIPT_PATH) {
            params.RELEASE_STAGE = releaseType
            params.RELEASE_SCOPE = releaseScope
        }

        when: "running buildWDKAutoSwitch pipeline"
        inSandbox { buildWDK(unityVersions: ["2018"], logLevel: "level") }
        then: "runs gradle with parameters"
        assertShCallsWith("gradlew",
                "-Prelease.stage=${releaseType}",
                "-Prelease.scope=${releaseScope}",
                "assemble")

        and: "has set up environment"
        def env = usedEnvironments.first()
        hasBaseEnvironment(env, "level")
        env.UNITY_LOG_CATEGORY == "build"
        env.UNITY_PACKAGE_MANAGER == "upm"
        env.UPM_USER_CONFIG_FILE == upmCredsFile.absolutePath

        and: "stashes gradle cache and build outputs"
        stash['wdk_output']["includes"] ==".gradle/**, **/build/outputs/**/*"

        where:
        releaseType | releaseScope
        "snapshot"  | "patch"
        "preflight" | "minor"
        "rc"        | "minor"
        "final"     | "major"
    }

    @Unroll("sets up WDK (UPM and Paket) for #releaseType-#releaseScope release")
    def "sets up WDK (UPM and Paket) with given release data"() {
        given: "needed credentials"
        credentials.addUsernamePassword("artifactory_read", "key", "pwd")
        and: "build plugin with publish parameters"
        def buildWDK = loadSandboxedScript(SCRIPT_PATH) {
            params.RELEASE_STAGE = releaseType
            params.RELEASE_SCOPE = releaseScope
        }

        when: "running buildWDKAutoSwitch pipeline"
        inSandbox {
            buildWDK(unityVersions: ["2018"], logLevel: "level", forceRefreshDependencies: forceRefreshDependencies)
        }

        then: "runs gradle with parameters"
        assertShCallsWith(2,"gradlew", //2 calls 1 for upm, 1 for paket
                "-Prelease.stage=${releaseType}",
                "-Prelease.scope=${releaseScope}",
                "setup")

        and: "stashes upm setup"
        stash['upm_setup_w'].with {
            assert useDefaultExcludes == true
            assert includes == "**/Packages/packages-lock.json, **/PackageCache/**, **/build/**"
        }
        and: "stashes paket setup"
        stash['paket_setup_w'].with {
            useDefaultExcludes == true
            includes == "paket.lock, .gradle/**, **/build/**, .paket/**, packages/**, paket-files/**, **/Paket.Unity3D/**, **/Wooga/Plugins/**"
        }
        //TODO: env asserts need a better way to associate environment with stage/step

        where:
        releaseType | releaseScope | forceRefreshDependencies
        "snapshot"  | "patch"      | false
        "preflight" | "patch"      | false
        "rc"        | "minor"      | false
        "final"     | "major"      | false
        "snapshot"  | "patch"      | true
        "preflight" | "patch"      | true
        "rc"        | "minor"      | true
        "final"     | "major"      | true
    }

    @Unroll("#description workspace steps when clearWs is #clearWs")
    def "clears steps if clearWs is set"() {
        given: "loaded check in a running jenkins build"
        def buildWDK = loadSandboxedScript(SCRIPT_PATH) {
            params.RELEASE_STAGE = "any"
            params.RELEASE_SCOPE = "any"
        }

        when: "running pipeline"
        inSandbox {
            buildWDK(unityVersions: ["2019"], clearWs: clearWs)
        }

        then: "all workspaces are clean"
        calls["cleanWs"].length == (clearWs? 4 : 0) //setup, build, hash, and publish steps

        where:
        clearWs << [true, false]
        description = clearWs? "clears" : "doesn't clear"
    }

    def hasBaseEnvironment(Map env, String logLevel) {
        env.with {
            UVM_AUTO_SWITCH_UNITY_EDITOR == "YES" &&
                    UVM_AUTO_INSTALL_UNITY_EDITOR == "YES" &&
                    LOG_LEVEL == logLevel &&
                    ATLAS_READ == this.credentials["artifactory_read"]
        }
    }

    def assertShCallsWith(int count = 1, String... elements) {
        def callArgs = calls["sh"].collect { it.args[0]["script"] as String }
        def found = callArgs.findAll { args ->
            elements.every { args.contains(it) }
        }
        assert found.size() == count,
                "${found.size()} sh call with arguments [${elements.join(", ")}] found in:\n [${pprintList(callArgs)}]"
        return found
    }

    def pprintList(List<?> list) {
        return list.collect { it.toString() }.join("\n")
    }

}
