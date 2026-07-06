package net.wooga.jenkins.pipeline.config

/**
 * Org-wide default private NuGet feed for .NET builds. Kept separate from
 * the generic Dotnet model class (src/net/wooga/jenkins/pipeline/model/Dotnet.groovy),
 * which accepts NuGet source/credentials as plain, optional constructor
 * parameters and has no built-in knowledge of Wooga specifics - these
 * defaults are applied only by the vars/withDotnet.groovy and
 * vars/dotnetWrapper.groovy step scripts (via mergeWithConfigMap, mirroring
 * PipelineConventions' convention), so callers needing different (e.g.
 * publish) credentials, or no NuGet feed at all, can override or opt out.
 */
class DotnetNugetConfig {

    static final DotnetNugetConfig standard = new DotnetNugetConfig(
            "wooga_nuget",
            "https://wooga.jfrog.io/artifactory/api/nuget/v3/wooga_nuget/index.json",
            "artifactory_read"
    )

    final String sourceName
    final String sourceUrl
    final String credentialsId

    DotnetNugetConfig(String sourceName, String sourceUrl, String credentialsId) {
        this.sourceName = sourceName
        this.sourceUrl = sourceUrl
        this.credentialsId = credentialsId
    }

    /**
     * Overrides this config's source/credentials with any nugetSourceName/
     * nugetSourceUrl/nugetCredentialsId present in configMap, or returns an
     * empty (no-NuGet) config when configMap.nuget == false.
     */
    DotnetNugetConfig mergeWithConfigMap(Map configMap) {
        if (configMap.nuget == false) {
            return new DotnetNugetConfig(null, null, null)
        }
        return new DotnetNugetConfig(
                (configMap.nugetSourceName ?: sourceName) as String,
                (configMap.nugetSourceUrl ?: sourceUrl) as String,
                (configMap.nugetCredentialsId ?: credentialsId) as String
        )
    }

    /**
     * This config's fields as the Map keys Dotnet.fromJenkins() expects.
     */
    Map toDotnetArgs() {
        return [nugetSourceName: sourceName, nugetSourceUrl: sourceUrl, nugetCredentialsId: credentialsId]
    }
}
