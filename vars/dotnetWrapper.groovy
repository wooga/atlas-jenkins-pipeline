#!/usr/bin/env groovy
import net.wooga.jenkins.pipeline.model.Dotnet

/**
 * provisions the requested .NET SDK and executes `dotnet <command>` against it
 */
def call(String command, Boolean returnStatus = false, Boolean returnStdout = false) {
    runCommand(Dotnet.fromJenkins(this), command, returnStatus, returnStdout)
}

def call(Map args) {
    def dotnet = Dotnet.fromJenkins(this, args)
    runCommand(dotnet, args.command?.toString(),
            (args.returnStatus ?: false) as Boolean,
            (args.returnStdout ?: false) as Boolean)
}

private def runCommand(Dotnet dotnet, String command, Boolean returnStatus, Boolean returnStdout) {
    return dotnet.withProvisionedEnv {
        if (isUnix()) {
            return sh(script: "dotnet ${command}", returnStdout: returnStdout, returnStatus: returnStatus)
        } else {
            return bat(script: "dotnet ${command}", returnStdout: returnStdout, returnStatus: returnStatus)
        }
    }
}
