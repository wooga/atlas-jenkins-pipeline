#!/usr/bin/env groovy
import net.wooga.jenkins.pipeline.model.Dotnet
import net.wooga.jenkins.pipeline.config.DotnetNugetConfig

/**
 * installs the requested .NET SDK and executes `dotnet <command>` against it
 */
def call(String command, Boolean returnStatus = false, Boolean returnStdout = false) {
    def dotnet = Dotnet.fromJenkins(this, DotnetNugetConfig.standard.toDotnetArgs())
    runCommand(dotnet, command, returnStatus, returnStdout)
}

def call(Map args) {
    def nuget = DotnetNugetConfig.standard.mergeWithConfigMap(args)
    def dotnet = Dotnet.fromJenkins(this, args + nuget.toDotnetArgs())
    runCommand(dotnet, args.command?.toString(),
            (args.returnStatus ?: false) as Boolean,
            (args.returnStdout ?: false) as Boolean)
}

private def runCommand(Dotnet dotnet, String command, Boolean returnStatus, Boolean returnStdout) {
    return dotnet.withInstalledDotnet {
        if (dotnet.isUnix()) {
            return sh(script: "dotnet ${command}", returnStdout: returnStdout, returnStatus: returnStatus)
        } else {
            return bat(script: "dotnet ${command}", returnStdout: returnStdout, returnStatus: returnStatus)
        }
    }
}
