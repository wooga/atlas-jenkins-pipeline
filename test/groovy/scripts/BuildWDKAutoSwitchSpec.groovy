package scripts

import com.lesfurets.jenkins.unit.MethodCall
import spock.lang.Unroll
import tools.DeclarativeJenkinsSpec

class BuildWDKAutoSwitchSpec extends DeclarativeJenkinsSpec {
    private static final String SCRIPT_PATH = "vars/buildWDKAutoSwitch.groovy"

    def setupSpec() {
    }

    @Unroll("publishes #releaseType-#releaseScope release ")
    def "publishes WDK with #release release type"() {
        given: "credentials holder with publish keys"
        credentials.addString("artifactory_read", "usr:pwd")
        credentials.addUsernamePassword('github_up', "usr", "pwd")
        and: "build plugin with publish parameters"
        def buildWDK = loadScript(SCRIPT_PATH) {
            params.RELEASE_TYPE = releaseType
            params.RELEASE_SCOPE = releaseScope
            env.GRGIT_USR = "usr"
            env.GRGIT_PSW = "pwd"
        }

        when: "running buildWDKAutoSwitch pipeline"
        buildWDK(unityVersions: ["2019"], logLevel: "level")

        then: "runs gradle with parameters"
        skipsRelease ^/*XOR*/calls.has["sh"] { MethodCall call ->
            String args = call.args[0]["script"]
            args.contains("gradlew") &&
                    args.contains(releaseType) &&
                    args.contains("-Ppaket.publish.repository='${releaseType}'") &&
                    args.contains("-Prelease.stage=${releaseType}") &&
                    args.contains("-Prelease.scope=${releaseScope}") &&
                    args.contains("-x check")
        }
        and: "has set up environment"
        def env = usedEnvironments.last()
        skipsRelease ^ hasBaseEnvironment(env, "level")
        skipsRelease ^ env.with {
            GRGIT == this.credentials['github_up'] &&
            GRGIT_USER == "usr" &&//"${GRGIT_USR}"
            GRGIT_PASS == "pwd" &&//"${GRGIT_PSW}"
            GITHUB_LOGIN == "usr" &&//"${GRGIT_USR}"
            GITHUB_PASSWORD == "pwd" &&//"${GRGIT_PSW}"
            UNITY_LOG_CATEGORY == "build"
        }

        where:
        releaseType | releaseScope | skipsRelease
        "snapshot"  | "patch"      | false
        "rc"        | "minor"      | false
        "final"     | "major"      | false
    }


    @Unroll("assemblies WDK for #releaseType-#releaseScope release ")
    def "assemblies WDK with given release data"() {
        given: "needed credentials"
        credentials.addUsernamePassword("artifactory_read", "key", "pwd")
        and: "build plugin with publish parameters"
        def buildWDK = loadScript(SCRIPT_PATH) {
            params.RELEASE_TYPE = releaseType
            params.RELEASE_SCOPE = releaseScope
        }

        when: "running buildWDKAutoSwitch pipeline"
        buildWDK(unityVersions: ["2018"], logLevel: "level")

        then: "runs gradle with parameters"
        calls.has["sh"] { MethodCall call ->
            String args = call.args[0]["script"]
            args.contains("gradlew") &&
                    args.contains("-Prelease.stage=${releaseType}") &&
                    args.contains("-Prelease.scope=${releaseScope}") &&
                    args.contains("assemble")
        }
        and: "has set up environment"
        def env = usedEnvironments.first()
        hasBaseEnvironment(env, "level")
        env.with {
            UNITY_LOG_CATEGORY == "build"
        }
        where:
        releaseType | releaseScope
        "snapshot"  | "patch"
        "rc"        | "minor"
        "final"     | "major"
    }

    @Unroll("sets up WDK for #releaseType-#releaseScope release ")
    def "sets up WDK with given release data"() {
        given: "needed credentials"
        credentials.addUsernamePassword("artifactory_read", "key", "pwd")
        and: "build plugin with publish parameters"
        def buildWDK = loadScript(SCRIPT_PATH) {
            params.RELEASE_TYPE = releaseType
            params.RELEASE_SCOPE = releaseScope
        }

        when: "running buildWDKAutoSwitch pipeline"
        buildWDK(unityVersions: ["2018"], logLevel: "level", forceRefreshDependencies: forceRefreshDependencies)

        then: "runs gradle with parameters"
        calls.has["sh"] { MethodCall call ->
            String args = call.args[0]["script"]
            args.contains("gradlew") &&
                    args.contains("-Prelease.stage=${releaseType}") &&
                    args.contains("-Prelease.scope=${releaseScope}") &&
                    args.contains("setup")
        }
        and: "refresh dependencies if flagged to"
        if(forceRefreshDependencies) {
            calls.has["sh"] { MethodCall call ->
                String args = call.args[0]["script"]
                args.contains("gradlew") &&
                args.contains("--refresh-dependencies")
            }
        }
        Map env = binding.env
        hasBaseEnvironment(env, "level")

        where:
        releaseType | releaseScope | forceRefreshDependencies
        "snapshot"  | "patch"      | false
        "rc"        | "minor"      | false
        "final"     | "major"      | false
        "snapshot"  | "patch"      | true
        "rc"        | "minor"      | true
        "final"     | "major"      | true
    }

    def hasBaseEnvironment(Map env, String logLevel) {
        env.with {
            UVM_AUTO_SWITCH_UNITY_EDITOR  == "YES" &&
            UVM_AUTO_INSTALL_UNITY_EDITOR == "YES" &&
            LOG_LEVEL == logLevel &&
            ATLAS_READ == this.credentials["artifactory_read"]
        }
    }

}
