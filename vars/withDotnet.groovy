#!/usr/bin/env groovy
import net.wooga.jenkins.pipeline.model.Dotnet
import net.wooga.jenkins.pipeline.config.DotnetNugetConfig

/**
 * installs the requested .NET SDK and runs the given block with it on PATH
 */
def call(Map config = [:], Closure block) {
    def nuget = DotnetNugetConfig.standard().mergeWithConfigMap(config)
    def dotnet = Dotnet.fromJenkins(this, config + nuget.toDotnetArgs())
    dotnet.withInstalledDotnet(block)
}
