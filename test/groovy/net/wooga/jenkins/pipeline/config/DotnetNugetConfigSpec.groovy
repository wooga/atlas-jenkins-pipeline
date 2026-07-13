package net.wooga.jenkins.pipeline.config

import spock.lang.Specification

class DotnetNugetConfigSpec extends Specification {

    def "mergeWithConfigMap keeps the defaults when the map has no overrides"() {
        given:
        def merged = DotnetNugetConfig.standard().mergeWithConfigMap([:])

        expect:
        merged.sourceName == DotnetNugetConfig.standard().sourceName
        merged.sourceUrl == DotnetNugetConfig.standard().sourceUrl
        merged.credentialsId == DotnetNugetConfig.standard().credentialsId
    }

    def "mergeWithConfigMap overrides individual fields given in the map"() {
        given:
        def merged = DotnetNugetConfig.standard().mergeWithConfigMap([
                nugetSourceName   : "custom_source",
                nugetSourceUrl    : "https://example.com/index.json",
                nugetCredentialsId: "custom_creds",
        ])

        expect:
        merged.sourceName == "custom_source"
        merged.sourceUrl == "https://example.com/index.json"
        merged.credentialsId == "custom_creds"
    }

    def "mergeWithConfigMap returns an empty config when nuget: false"() {
        given:
        def merged = DotnetNugetConfig.standard().mergeWithConfigMap([nuget: false, nugetSourceName: "ignored"])

        expect:
        merged.sourceName == null
        merged.sourceUrl == null
        merged.credentialsId == null
    }

    def "toDotnetArgs exposes the fields as Dotnet.fromJenkins() keys"() {
        given:
        def config = new DotnetNugetConfig("name", "url", "creds")

        expect:
        config.toDotnetArgs() == [nugetSourceName: "name", nugetSourceUrl: "url", nugetCredentialsId: "creds"]
    }
}
