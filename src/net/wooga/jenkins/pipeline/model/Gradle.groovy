package net.wooga.jenkins.pipeline.model

class Gradle {

    private String logLevel
    private boolean stackTrace

    static Gradle fromJenkins(Object jenkinsScript, String logLevel, boolean stackTrace) {
        return new Gradle(jenkinsScript, logLevel, stackTrace)
    }

    Object jenkins

    Gradle(Object jenkins, String logLevel, boolean stackTrace) {
        this.stackTrace = stackTrace
        this.logLevel = logLevel
        this.jenkins = jenkins
    }

    /**
     * execute gradlew or gradlew.bat based on current os
     */
    def wrapper(String command, Boolean returnStatus = false, Boolean returnStdout = false) {

        if (jenkins.isUnix()) {
            return jenkins.sh(script: "./gradlew ${formatCommand(command)}", returnStdout: returnStdout, returnStatus: returnStatus)
        } else {
            return jenkins.bat(script: "gradlew.bat ${formatCommand(command)}", returnStdout: returnStdout, returnStatus: returnStatus)
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

        if (!containsOptions(result, "quiet", "warn", "info", "debug")) {
            if (logLevel) {
                result += " --${logLevel}"
            }
        }

        if (stackTrace && !containsOptions(result, "stacktrace")) {
            result += " --stacktrace"
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
