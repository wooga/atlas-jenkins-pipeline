#!/usr/bin/env groovy
import net.wooga.jenkins.pipeline.model.Dotnet

/**
 * provisions the requested .NET SDK, installs packageId as a local dotnet
 * tool, and runs the given block with it invocable via `dotnet tool run`
 */
def call(String packageId, Closure block) {
    def dotnet = Dotnet.fromJenkins(this)
    return dotnet.withTool(packageId, null, block)
}

def call(Map opts, Closure block) {
    def dotnet = Dotnet.fromJenkins(this)
    return dotnet.withTool(opts.packageId?.toString(), opts.version as String, block)
}
