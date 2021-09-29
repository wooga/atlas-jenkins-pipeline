package net.wooga.jenkins.pipeline.setup

import net.wooga.jenkins.pipeline.model.Gradle

class Setups {

    Object j
    Gradle gradle
    boolean forceRefreshDependencies


    Setups(Object jenkinsScript, Gradle gradle, boolean forceRefreshDependencies) {
        this.j = jenkinsScript
        this.gradle = gradle
        this.forceRefreshDependencies = forceRefreshDependencies
    }

    static Setups forJenkins(Object jenkinsScript, Gradle gradle, boolean forceRefreshDependencies) {
        return new Setups(jenkinsScript, gradle, forceRefreshDependencies)
    }

    def wdk(String releaseType, String releaseScope) {
        if(forceRefreshDependencies) {
            gradle.wrapper("--refresh-dependencies")
        }
        gradle.wrapper("-Prelease.stage=${releaseType.trim()} " +
                        "-Prelease.scope=${releaseScope.trim()} setup")
    }

}
