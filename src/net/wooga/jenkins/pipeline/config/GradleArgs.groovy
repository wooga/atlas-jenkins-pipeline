package net.wooga.jenkins.pipeline.config

class GradleArgs {

    final String logLevel
    final boolean stackTrace
    final boolean refreshDependencies


    static GradleArgs fromConfigMap(Map config) {
        def showStackTraces = (config.showStackTrace?:false) as boolean
        def refreshDependencies = (config.refreshDependencies?:false) as boolean
        return new GradleArgs(config.logLevel as String, showStackTraces, refreshDependencies)
    }

    GradleArgs(String logLevel, boolean stackTrace, boolean refreshDependencies) {
        this.logLevel = logLevel
        this.stackTrace = stackTrace
        this.refreshDependencies = refreshDependencies
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
