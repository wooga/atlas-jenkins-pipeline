package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.model.EnvVars
import net.wooga.jenkins.pipeline.model.Gradle

class GradleArgs {

    final EnvVars environment
    final String logLevel
    final boolean stackTrace
    final boolean refreshDependencies


    static GradleArgs fromConfigMap(Map config) {
        def environment = EnvVars.fromList((config.gradleEnvironment ?: []) as List<?>)
        def showStackTraces = (config.showStackTrace ?: false) as boolean
        def refreshDependencies = (config.refreshDependencies ?: false) as boolean
        return new GradleArgs(environment, config.logLevel as String, showStackTraces, refreshDependencies)
    }


    GradleArgs(EnvVars environment, String logLevel, boolean stackTrace, boolean refreshDependencies) {
        this.environment = environment
        this.logLevel = logLevel
        this.stackTrace = stackTrace
        this.refreshDependencies = refreshDependencies
    }

    Gradle createGradle(Object jenkins) {
        return Gradle.fromJenkins(jenkins, this)
    }

    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        GradleArgs that = (GradleArgs) o

        if (refreshDependencies != that.refreshDependencies) return false
        if (stackTrace != that.stackTrace) return false
        if (logLevel != that.logLevel) return false

        return true
    }

    int hashCode() {
        int result
        result = (logLevel != null ? logLevel.hashCode() : 0)
        result = 31 * result + (stackTrace ? 1 : 0)
        result = 31 * result + (refreshDependencies ? 1 : 0)
        return result
    }
}
