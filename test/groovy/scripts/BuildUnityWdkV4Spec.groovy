package scripts


import spock.lang.Unroll
import tools.DeclarativeJenkinsSpec

class BuildUnityWdkV4Spec extends DeclarativeJenkinsSpec {
    private static final String SCRIPT_PATH = "vars/buildUnityWdkV4.groovy"

    @Unroll("publishes Default or Autoref WDK depending on versions #autoref")
    def "publishes Default or Autoref WDK depending on versions #packageType"() {
        given: "credentials holder with publish keys"
        credentials.addUsernamePassword("artifactory_publish", "artifactory_publish_usr", "artifactory_publish_pwd")
        credentials.addUsernamePassword("artifactory_deploy", "artifactory_deploy_usr", "artifactory_deploy_pwd")
        credentials.addUsernamePassword('github_access', "github_access_usr", "github_access_pwd")
        and: "build plugin with publish parameters"
        def buildWDK = loadSandboxedScript(SCRIPT_PATH) {
            params.RELEASE_STAGE = "any"
            params.RELEASE_SCOPE = "any"
            env.GRGIT_USR = "usr"
            env.GRGIT_PSW = "pwd"
        }
        def packageSetupStash = "${stashName}_setup_w" as String

        when: "running buildWDKAutoSwitch pipeline"
        println packageSetupStash
        inSandbox { buildWDK(unityVersions: [[version :"2019"]], clearWs: true, autoref: autoref) }

        then: "stashes #stashName setup"
        stash.containsKey(packageSetupStash)
        and: "don't stashes other paket setup"
        def numberOfSetupStashes = stash.findAll { it.key.endsWith("_setup_w") }.size()
        numberOfSetupStashes == autoref ? 2 : 1

        then: "runs gradle with parameters"
        assertShCallsWith(autoref ? 2 : 1, "gradlew",
                "publish",
                "-Pupm.repository='any'",
                "-PversionBuilder.stage=any",
                "-PversionBuilder.scope=any"
        ) //2 calls when also building autoref, 1 when not

        cleanup:
        stash.clear()

        where:
        autoref   | stashName
        true      | "autoref"
        false     | "default"
    }

    @Unroll("publishes Default/Autoref WDK #releaseType-#releaseScope release")
    def "publishes Default/Autoref WDK with #release release type"() {
        given: "credentials holder with publish keys"
        credentials.addUsernamePassword("artifactory_publish", "artifactory_publish_usr", "artifactory_publish_pwd")
        credentials.addUsernamePassword("artifactory_deploy", "artifactory_deploy_usr", "artifactory_deploy_pwd")
        credentials.addUsernamePassword('github_access', "github_access_usr", "github_access_pwd")
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
        assertShCallsWith(2, "gradlew",
                "publish",
                "-Pupm.repository='${releaseType?:"snapshot"}'",
                "-PversionBuilder.stage=${releaseType?:"snapshot"}",
                "${releaseScope? "-PversionBuilder.scope=${releaseScope}": ''}"
        ) //2 calls 1 for autoref, 1 for default
        and: "has set up environment"
        for (int i = 1 ; i <= 2; i++) {
            def env = usedEnvironments[usedEnvironments.size() - i]
            hasBaseEnvironment(env, "level")
            env.GRGIT == this.credentials['github_access']
            env.GRGIT_USER == "usr" //"${GRGIT_USR}"
            env.GRGIT_PASS == "pwd" //"${GRGIT_PSW}"
            env.GITHUB_LOGIN == "usr" //"${GRGIT_USR}"
            env.GITHUB_PASSWORD == "pwd" //"${GRGIT_PSW}"
            env.UNITY_PACKAGE_MANAGER == "upm"
            env.UNITY_LOG_CATEGORY == "build"
            env.UPM_USERNAME == artifactory_user //artifactory_publish user
            env.UPM_PASSWORD == artifactory_password //artifactory_publish password
            env.NUGET_KEY == this.credentials[artifactory_credentials]
            env.WDK_SETUP_AUTOREF == (i == 1) ? "true" : "false"
        }

        where:
        releaseType | releaseScope
        null        | null
        "snapshot"  | "patch"
        "preflight" | "minor"
        "rc"        | "minor"
        "final"     | "major"

        github_user = "github_access_usr"
        github_password = "github_access_pwd"
        artifactory_deploy_user = "artifactory_deploy_usr"
        artifactory_deploy_password = "artifactory_deploy_pwd"
        artifactory_publish_user = "artifactory_publish_usr"
        artifactory_publish_password = "artifactory_publish_pwd"

        artifactory_user = releaseType == "snapshot" || releaseType == null ? artifactory_publish_user : artifactory_deploy_user
        artifactory_password = releaseType == "snapshot" || releaseType == null ? artifactory_publish_password : artifactory_deploy_password

        artifactory_credentials = releaseType == "snapshot" || releaseType == null ? "artifactory_publish" : "artifactory_deploy"
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
        def env = usedEnvironments.last()
        hasBaseEnvironment(env, "level")
        env.UNITY_LOG_CATEGORY == "build"
        env.UPM_USER_CONFIG_FILE == upmCredsFile.absolutePath

        and: "stashes gradle cache and build outputs"
        stash['wdk_output']["includes"] == ".gradle/**, **/build/outputs/**/*"

        where:
        releaseType | releaseScope
        "snapshot"  | "patch"
        "preflight" | "minor"
        "rc"        | "minor"
        "final"     | "major"
    }

    @Unroll("sets up WDK (Default and Autoreferenced) for #releaseType-#releaseScope release")
    def "sets up WDK (Default and Autoreferenced) with given release data"() {
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
        assertShCallsWith(2, "gradlew", //2 calls 1 for autoref, 1 for default
                "-Prelease.stage=${releaseType}",
                "-Prelease.scope=${releaseScope}",
                "setup")

        and: "stashes upm setup"
        stash['default_setup_w'].with {
            assert useDefaultExcludes == true
            assert includes == ".gradle/**, **/build/**, Packages/**, **/PackageCache/**"
        }
        and: "stashes paket setup"
        stash['autoref_setup_w'].with {
            useDefaultExcludes == true
            includes == ".gradle/**, **/build/**, Packages/**, **/PackageCache/**"
        }

        and: "sets up environment"
        def env = usedEnvironments[0]   // this is the setup autoref environment
        env.WDK_SETUP_AUTOREF == "true"
        def env2 = usedEnvironments[1]  // this is the setup default environment
        env2.WDK_SETUP_AUTOREF == "false"

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
        calls["cleanWs"].length == (clearWs ? 5 : 0) //setup x2, build, and publish x2 steps

        where:
        clearWs << [true, false]
        description = clearWs ? "clears" : "doesn't clear"
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
