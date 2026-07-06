#!/usr/bin/env groovy
import net.wooga.jenkins.pipeline.model.Dotnet

/**
 * provisions the requested .NET SDK, installs packageId as a local dotnet
 * tool, and runs toolBinary against it via `dotnet tool run`
 */
def call(String packageId, String toolBinary, List<String> args = []) {
    def dotnet = Dotnet.fromJenkins(this)
    return dotnet.runTool(packageId, toolBinary, args, null, false)
}

def call(Map args) {
    def dotnet = Dotnet.fromJenkins(this)
    return dotnet.runTool(
            args.packageId?.toString(),
            args.toolBinary?.toString(),
            (args.args ?: []) as List<String>,
            args.version as String,
            (args.returnStatus ?: false) as Boolean)
}
