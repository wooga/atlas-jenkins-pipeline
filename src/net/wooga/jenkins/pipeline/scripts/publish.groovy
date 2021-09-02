package net.wooga.jenkins.pipeline.scripts

def call(String releaseType, String releaseScope) {
    return [
       gradlePlugin: this.&gradlePlugin.curry(releaseType, releaseScope),
       bintray: this.&bintray.curry(releaseType, releaseScope),
       ossrh: this.&ossrh.curry(releaseType, releaseScope)
    ]
}

def gradlePlugin(String releaseType, String releaseScope,
                 String publishKeySecret, String publishSecretSecret) {
    withCredentials([string(credentialsId: publishKeySecret, variable: "GRADLE_PUBLISH_KEY"),
                     string(credentialsId: publishSecretSecret, variable: "GRADLE_PUBLISH_SECRET")]) {
        gradleWrapper "${releaseType.trim()} " +
                        "-Pgradle.publish.key=${GRADLE_PUBLISH_KEY} " +
                        "-Pgradle.publish.secret=${GRADLE_PUBLISH_SECRET} " +
                        "-Prelease.stage=${releaseType.trim()} " +
                        "-Prelease.scope=${releaseScope.trim()} -x check"
    }
}

def bintray(String releaseType, String releaseScope,
            String bintraySecret) {
    withCredentials([usernamePassword(credentialsId: bintraySecret, usernameVariable: "BINTRAY_USER",
                                                                    passwordVariable: "BINTRAY_API_KEY")]) {
        gradleWrapper "${releaseType.trim()} " +
                        "-Pbintray.user=${BINTRAY_USER} " +
                        "-Pbintray.key=${BINTRAY_API_KEY} " +
                        "-Prelease.stage=${releaseType.trim()} " +
                        "-Prelease.scope=${releaseScope.trim()} -x check"
    }
}

def ossrh(String releaseType, String releaseScope,
          String publishSecret, String signingKeySecret, String signingKeyIdSecret, String signingPassphraseSecret) {
    def credentials = [usernamePassword(credentialsId: publishSecret,
                            usernameVariable:"OSSRH_USERNAME", passwordVariable: "OSSRH_PASSWORD")] +
            ossrhSigningCredentials(signingKeySecret, signingKeyIdSecret, signingPassphraseSecret)
    withCredentials(credentials) {
        gradleWrapper "${releaseType.trim()} " +
                "-Prelease.stage=${releaseType.trim()} " +
                "-Prelease.scope=${releaseScope.trim()} -x check"
    }
}

def artifactoryOSSRH(String releaseType, String releaseScope, String artifactorySecret,
                String signingKeySecret, String signingKeyIdSecret, String signingPassphraseSecret) {
    def credentials = [usernamePassword(credentialsId: artifactorySecret,
            usernameVariable:"ARTIFACTORY_USER", passwordVariable: "ARTIFACTORY_PASS")] +
            ossrhSigningCredentials(signingKeySecret, signingKeyIdSecret, signingPassphraseSecret)
    withCredentials(credentials) {
        gradleWrapper "${releaseType.trim()} " +
                "-Partifactory.user=${ARTIFACTORY_USER} " +
                "-Partifactory.password=${ARTIFACTORY_PASS} " +
                "-Prelease.stage=${releaseType.trim()} " +
                "-Prelease.scope=${releaseScope.trim()} -x check"
    }
}

def ossrhSigningCredentials(String keySecret, String keyIdSecret, String passphraseSecret) {
    return [
        string(credentialsId: signingKeySecret, variable: "OSSRH_SIGNING_KEY"),
        string(credentialsId: signingKeyIdSecret, variable: "OSSRH_SIGNING_KEY_ID"),
        string(credentialsId: signingPassphraseSecret, variable: "OSSRH_SIGNING_PASSPHRASE")
    ]
}



