package net.wooga.jenkins.pipeline.publish

import net.wooga.jenkins.pipeline.model.Gradle

class Publishers {

    static Publishers fromJenkins(Object jenkinsScript, Gradle gradle, String releaseType, String releaseScope) {
        return new Publishers(jenkinsScript, gradle, releaseType, releaseScope)
    }

    final Object j
    final Gradle gradle
    final String releaseType
    final String releaseScope

    Publishers(Object jenkinsScript, Gradle gradle, String releaseType, String releaseScope) {
        this.j = jenkinsScript
        this.gradle = gradle
        this.releaseScope = releaseScope.trim()
        this.releaseType = releaseType.trim()
    }

    def gradlePlugin(String publishKeySecret, String publishSecretSecret) {
        j.withCredentials([j.string(credentialsId: publishKeySecret, variable: "GRADLE_PUBLISH_KEY"),
                         j.string(credentialsId: publishSecretSecret, variable: "GRADLE_PUBLISH_SECRET")]) {
            gradle.wrapper("${releaseType} " +
                    "-Pgradle.publish.key=${GRADLE_PUBLISH_KEY} " +
                    "-Pgradle.publish.secret=${GRADLE_PUBLISH_SECRET} " +
                    "-Prelease.stage=${releaseType} " +
                    "-Prelease.scope=${releaseScope} -x check")
        }
    }

    def bintray(String bintraySecret) {
        j.withCredentials([j.usernamePassword(credentialsId: bintraySecret, usernameVariable: "BINTRAY_USER",
                passwordVariable: "BINTRAY_API_KEY")]) {
            gradle.wrapper("${releaseType} " +
                        "-Pbintray.user=${BINTRAY_USER} " +
                        "-Pbintray.key=${BINTRAY_API_KEY} " +
                        "-Prelease.stage=${releaseType} " +
                        "-Prelease.scope=${releaseScope} -x check")
        }
    }

    def ossrh(String publishSecret, String signingKeySecret, String signingKeyIdSecret, String signingPassphraseSecret) {
        def credentials = [j.usernamePassword(credentialsId: publishSecret,
                usernameVariable:"OSSRH_USERNAME", passwordVariable: "OSSRH_PASSWORD")] +
                ossrhSigningCredentials(signingKeySecret, signingKeyIdSecret, signingPassphraseSecret)
        j.withCredentials(credentials) {
            gradle.wrapper("${releaseType} " +
                    "-Prelease.stage=${releaseType} " +
                    "-Prelease.scope=${releaseScope} -x check")
        }
    }

    def artifactoryOSSRH(String artifactorySecret,
                         String signingKeySecret, String signingKeyIdSecret, String signingPassphraseSecret) {
        def credentials = [j.usernamePassword(credentialsId: artifactorySecret, usernameVariable:"ARTIFACTORY_USER", passwordVariable: "ARTIFACTORY_PASS")] +
                ossrhSigningCredentials(signingKeySecret, signingKeyIdSecret, signingPassphraseSecret)
        j.withCredentials(credentials) {
            gradle.wrapper("${releaseType} " +
                    "-Partifactory.user=${ARTIFACTORY_USER} " +
                    "-Partifactory.password=${ARTIFACTORY_PASS} " +
                    "-Prelease.stage=${releaseType} " +
                    "-Prelease.scope=${releaseScope} -x check")
        }
    }

    def ossrhSigningCredentials(String keySecret, String keyIdSecret, String passphraseSecret) {
        return [
                j.string(credentialsId: keySecret, variable: "OSSRH_SIGNING_KEY"),
                j.string(credentialsId: keyIdSecret, variable: "OSSRH_SIGNING_KEY_ID"),
                j.string(credentialsId: passphraseSecret, variable: "OSSRH_SIGNING_PASSPHRASE")
        ]
    }

    def unityArtifactoryPaket(String unityPath, String artifactorySecret) {
        j.withEnv(["UNITY_PATH=${unityPath}", "UNITY_LOG_CATEGORY=build"]) {
            j.withCredentials([j.usernameColonPassword(credentialsId: artifactorySecret, variable: "NUGET_KEY"),
                               j.usernameColonPassword(credentialsId: artifactorySecret, variable: "nugetkey")]) {
                gradle.wrapper("${releaseType} " +
                                    "-Prelease.stage=${releaseType} " +
                                    "-Ppaket.publish.repository='${releaseType}' " +
                                    "-Prelease.scope=${releaseScope} -x check")
            }
        }
    }

}
