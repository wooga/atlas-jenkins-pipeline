package net.wooga.jenkins.pipeline.model

import net.wooga.jenkins.pipeline.config.GradleArgs
import net.wooga.jenkins.pipeline.config.JavaVersion

class Gradle {

    private Object jenkins
    private EnvVars environment
    private String logLevel
    private boolean stackTrace
    private boolean refreshDependencies
    private Integer javaVersion

    static Gradle fromJenkins(Object jenkinsScript, GradleArgs gradleArgs) {
        return new Gradle(jenkinsScript, gradleArgs.environment, gradleArgs.logLevel, gradleArgs.stackTrace, gradleArgs.refreshDependencies, gradleArgs.javaVersion)
    }

    Gradle(Object jenkins, EnvVars environment, String logLevel, boolean stackTrace, boolean refreshDependencies, Integer javaVersion) {
        this.environment = environment
        this.stackTrace = stackTrace
        this.logLevel = logLevel
        this.jenkins = jenkins
        this.refreshDependencies = refreshDependencies
        this.javaVersion = javaVersion
    }

    /**
     * execute gradlew or gradlew.bat based on current os
     */
    def wrapper(String command, Boolean returnStatus = false,
                Boolean returnStdout = false) {
        def javaHomeEnv = javaVersion? JavaVersion.javaHomeEnv(jenkins, javaVersion) : []
        jenkins.withEnv(environment.resolveAsStrings() + javaHomeEnv) {
            if (jenkins.isUnix()) {
                return jenkins.sh(script: "./gradlew ${formatCommand(command)}", returnStdout: returnStdout, returnStatus: returnStatus)
            } else {
                return jenkins.bat(script: "gradlew.bat ${formatCommand(command)}", returnStdout: returnStdout, returnStatus: returnStatus)
            }
        }
    }

    def wrapper(String umask, String command, Boolean returnStatus = false,
                Boolean returnStdout = false) {
        def javaHomeEnv = javaVersion? JavaVersion.javaHomeEnv(jenkins, javaVersion) : []
        jenkins.withEnv(environment.resolveAsStrings() + javaHomeEnv) {
            if (jenkins.isUnix()) {
                if (umask != null && umask != "") {
                    def numericUmask = umask.replaceAll("[^\\d]", "")
                    return jenkins.sh(script: "umask $numericUmask && ./gradlew ${formatCommand(command)}", returnStdout: returnStdout, returnStatus: returnStatus)
                }
                return jenkins.sh(script: "./gradlew ${formatCommand(command)}", returnStdout: returnStdout, returnStatus: returnStatus)
            } else {
                return jenkins.bat(script: "gradlew.bat ${formatCommand(command)}", returnStdout: returnStdout, returnStatus: returnStatus)
            }
        }
    }

    /**
     * @param command
     * @return A formatted command for the gradle process. If a log level was provided by the
     * environment it will be appended to the command.
     */
    protected String formatCommand(String command) {
        def regex = /-P[\w\d.]+=\s/
        def result = command.replaceAll(regex, '')

        if (!containsOptions(result, "quiet", "warn", "info", "debug") && logLevel) {
            result += " --${logLevel}"
        }
        if (stackTrace && !containsOptions(result, "stacktrace")) {
            result += " --stacktrace"
        }
        if (refreshDependencies && !containsOptions(result, "refresh-dependencies")) {
            result += " --refresh-dependencies"
        }

        return result
    }

    /**
     * @param command A valid gradle command
     * @param options One or more valid command-line options for gradle
     * @return True if the command contains one of the given options
     */
    protected static boolean containsOptions(String command, String... options) {
        def optionPattern = options.join('|')
        def pattern = ~/\s+--(${optionPattern})\s?/
        def matcher = command =~ pattern
        return matcher.find()
    }

}
