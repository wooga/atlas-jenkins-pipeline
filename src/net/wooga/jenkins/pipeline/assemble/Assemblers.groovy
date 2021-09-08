package net.wooga.jenkins.pipeline.assemble

import net.wooga.jenkins.pipeline.model.Gradle

class Assemblers {

    static Assemblers fromJenkins(Object jenkinsScript, String releaseType, String releaseScope) {
        return new Assemblers(jenkinsScript, Gradle.fromJenkins(jenkinsScript), releaseType, releaseScope)
    }

    final Object j
    final Gradle gradle
    final String releaseType
    final String releaseScope

    Assemblers(Object jenkinsScript, Gradle gradle, String releaseType, String releaseScope) {
        this.j = jenkinsScript
        this.gradle = gradle
        this.releaseScope = releaseScope.trim()
        this.releaseType = releaseType.trim()
    }

    def unityWDK(String unityLogCategory) {
        j.withEnv(["UNITY_LOG_CATEGORY = ${unityLogCategory}"]) {
            gradle.wrapper("-Prelease.stage=${releaseType} -Prelease.scope=${releaseScope} assemble")
        }
    }

}
