#!/usr/bin/env groovy
import net.wooga.jenkins.pipeline.model.Dotnet

/**
 * provisions the requested .NET SDK and runs the given block with it on PATH
 */
def call(Map config = [:], Closure block) {
    def dotnet = Dotnet.fromJenkins(this, config)
    dotnet.withProvisionedEnv(block)
}
