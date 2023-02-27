package net.wooga.jenkins.pipeline.publish

import net.wooga.jenkins.pipeline.config.PipelineConventions
import net.wooga.jenkins.pipeline.model.Gradle

class Publishers {

    static Publishers fromJenkins(Object jenkinsScript, Gradle gradle, String releaseType, String releaseScope) {
        return new Publishers(jenkinsScript, gradle, releaseType, releaseScope)
    }

    final Object jenkins
    final Gradle gradle
    final String releaseType
    final String releaseScope

    Publishers(Object jenkinsScript, Gradle gradle, String releaseType, String releaseScope) {
        this.jenkins = jenkinsScript
        this.gradle = gradle
        this.releaseScope = releaseScope.trim()
        this.releaseType = releaseType.trim()
    }

    def gradlePlugin(String publishKeySecret, String publishSecretSecret,
                     String checkTask = PipelineConventions.standard.checkTask) {
        jenkins.withCredentials([jenkins.string(credentialsId: publishKeySecret, variable: "GRADLE_PUBLISH_KEY"),
                                 jenkins.string(credentialsId: publishSecretSecret, variable: "GRADLE_PUBLISH_SECRET")]) {
            gradle.wrapper("${releaseType} " +
                    "-Pgradle.publish.key=${jenkins.GRADLE_PUBLISH_KEY} " +
                    "-Pgradle.publish.secret=${jenkins.GRADLE_PUBLISH_SECRET} " +
                    "-Prelease.stage=${releaseType} " +
                    "-Prelease.scope=${releaseScope} -x ${checkTask}")
        }
    }

    def bintray(String bintraySecret, String checkTask = PipelineConventions.standard.checkTask) {
        jenkins.withCredentials([jenkins.usernamePassword(credentialsId: bintraySecret, usernameVariable: "BINTRAY_USER",
                passwordVariable: "BINTRAY_API_KEY")]) {
            gradle.wrapper("${releaseType} " +
                        "-Pbintray.user=${jenkins.BINTRAY_USER} " +
                        "-Pbintray.key=${jenkins.BINTRAY_API_KEY} " +
                        "-Prelease.stage=${releaseType} " +
                        "-Prelease.scope=${releaseScope} -x ${checkTask}")
        }
    }

    def ossrh(String publishSecret, String signingKeySecret, String signingKeyIdSecret, String signingPassphraseSecret,
              String checkTask = PipelineConventions.standard.checkTask) {
        def credentials = [jenkins.usernamePassword(credentialsId: publishSecret,
                usernameVariable:"OSSRH_USERNAME", passwordVariable: "OSSRH_PASSWORD")] +
                ossrhSigningCredentials(signingKeySecret, signingKeyIdSecret, signingPassphraseSecret)
        jenkins.withCredentials(credentials) {
            gradle.wrapper("${releaseType} " +
                    "-Prelease.stage=${releaseType} " +
                    "-Prelease.scope=${releaseScope} -x ${checkTask}")
        }
    }

    def artifactoryOSSRH(String artifactorySecret,
                         String signingKeySecret, String signingKeyIdSecret, String signingPassphraseSecret,
                         String checkTask = PipelineConventions.standard.checkTask) {
        def credentials = [
            jenkins.usernamePassword(credentialsId: artifactorySecret,
                                    usernameVariable:"ARTIFACTORY_USER",
                                    passwordVariable: "ARTIFACTORY_PASS")
        ] + ossrhSigningCredentials(signingKeySecret, signingKeyIdSecret, signingPassphraseSecret)
        jenkins.withCredentials(credentials) {
            gradle.wrapper("${releaseType} " +
                    "-Partifactory.user=${jenkins.ARTIFACTORY_USER} " +
                    "-Partifactory.password=${jenkins.ARTIFACTORY_PASS} " +
                    "-Prelease.stage=${releaseType} " +
                    "-Prelease.scope=${releaseScope} -x ${checkTask}")
        }
    }

    def ossrhSigningCredentials(String keySecret, String keyIdSecret, String passphraseSecret) {
        return [
                jenkins.string(credentialsId: keySecret, variable: "OSSRH_SIGNING_KEY"),
                jenkins.string(credentialsId: keyIdSecret, variable: "OSSRH_SIGNING_KEY_ID"),
                jenkins.string(credentialsId: passphraseSecret, variable: "OSSRH_SIGNING_PASSPHRASE")
        ]
    }

    def unityArtifactoryPaket(String unityPath, String artifactorySecret,
                              String checkTask = PipelineConventions.standard.checkTask) {
        jenkins.withEnv(["UNITY_PACKAGE_MANAGER = paket", "UNITY_PATH=${unityPath}", "UNITY_LOG_CATEGORY=build"]) {
            jenkins.withCredentials([jenkins.usernameColonPassword(credentialsId: artifactorySecret, variable: "NUGET_KEY"),
                                     jenkins.usernameColonPassword(credentialsId: artifactorySecret, variable: "nugetkey")]) {
                gradle.wrapper("${releaseType} " +
                                    "-Prelease.stage=${releaseType} " +
                                    "-Ppaket.publish.repository='${releaseType}' " +
                                    "-Prelease.scope=${releaseScope} -x ${checkTask}")
            }
        }
    }

    def npm(String npmCredsSecret) {
        def credentials = [
            jenkins.usernamePassword(credentialsId: npmCredsSecret,
                                    usernameVariable:"NODE_RELEASE_NPM_USER",
                                    passwordVariable: "NODE_RELEASE_NPM_PASS")
        ]
        jenkins.withCredentials(credentials) {
            gradle.wrapper("${releaseType} -Prelease.stage=${releaseType} -Prelease.scope=${releaseScope} -x check")
        }
    }

    def unityArtifactoryUpm(String artifactorySecret) {
        jenkins.withEnv(["UNITY_PACKAGE_MANAGER = upm", "UNITY_LOG_CATEGORY=build"]) {
            jenkins.withCredentials([jenkins.usernamePassword(credentialsId: artifactorySecret, usernameVariable:"UPM_USERNAME", passwordVariable: "UPM_PASSWORD"),
                                     jenkins.usernameColonPassword(credentialsId: artifactorySecret, variable: "NUGET_KEY"),
                                     jenkins.usernameColonPassword(credentialsId: artifactorySecret, variable: "nugetkey")]) {


                gradle.wrapper("publish " +
                        "-Ppaket.publish.repository='${releaseType}' " +
                        "-Ppublish.repository='${releaseType}' " +
                        "-PversionBuilder.stage=${releaseType} " +
                        "-PversionBuilder.scope=${releaseScope}")
            }
        }
    }
}
