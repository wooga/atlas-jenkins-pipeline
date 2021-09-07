package net.wooga.jenkins.pipeline.model

class Gradle {

    static Gradle fromJenkins(Object jenkinsScript) {
        return new Gradle(jenkinsScript, jenkinsScript.params, jenkinsScript.env)
    }

    Object jenkins
    private Map<String, Object> params
    private Map<String, Object> env

    Gradle(Object jenkins, Map<String, Object> params, Map<String, Object> env) {
        this.jenkins = jenkins
        this.params = params
        this.env = env
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
            if (params.LOG_LEVEL) {
                result += " --${params.LOG_LEVEL}"
            } else if (env.LOG_LEVEL) {
                result += " --${env.LOG_LEVEL}"
            }
        }

        if (params.STACK_TRACE == true && !containsOptions(result, "stacktrace")) {
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
