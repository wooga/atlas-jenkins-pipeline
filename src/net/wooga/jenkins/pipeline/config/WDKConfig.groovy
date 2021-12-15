package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.BuildVersion
import net.wooga.jenkins.pipeline.PipelineTools

import java.nio.file.Files
import java.nio.file.Paths

class WDKConfig implements PipelineConfig {

    private static File searchFile(String baseDir, String fileName) {
        def walkStream = Files.walk(Paths.get(baseDir))
        try {
            return walkStream.
                    filter{ p -> p.toFile().isFile() }.
                    filter{ f -> f.toString().endsWith(fileName) }.
                    findFirst().map{it.toFile() }.orElse(null);
        } finally {
            walkStream.close()
        }
    }

    static WDKConfig fromConfigMap(String buildLabel, Map configMap, Object jenkinsScript) {
        configMap.unityVersions = configMap.unityVersions ?: []
        def unityVerObjs = configMap.unityVersions as List
        def projectVersionFile = configMap.projectVersionFile ?
                                        new File(configMap.projectVersionFile as String) :
                                        searchFile(".", "ProjectVersion.txt")
        def index = 0
        def unityVersions = unityVerObjs.collect { Object unityVersionObj ->
            def buildVersion = BuildVersion.parse(unityVersionObj, projectVersionFile)
            def platform = Platform.forWDK(buildVersion, buildLabel, configMap, index == 0)
            index++
            return new UnityVersionPlatform(platform, buildVersion)
        }
        if (unityVersions.isEmpty()) throw new IllegalArgumentException("Please provide at least one unity version.")

        def gradleArgs = GradleArgs.fromConfigMap(configMap)
        def jenkinsMetadata = JenkinsMetadata.fromScript(jenkinsScript)
        def checkArgs = CheckArgs.fromConfigMap(jenkinsScript, jenkinsMetadata, configMap)
        def conventions = PipelineConventions.standard.mergeWithConfigMap(configMap)

        return new WDKConfig(jenkinsScript, unityVersions, checkArgs, gradleArgs, jenkinsMetadata, buildLabel, conventions)
    }

    final Object jenkins
    final UnityVersionPlatform[] unityVersions
    final CheckArgs checkArgs
    final GradleArgs gradleArgs
    final JenkinsMetadata metadata
    final String buildLabel
    final PipelineConventions conventions

    WDKConfig(Object jenkins, List<UnityVersionPlatform> unityVersions, CheckArgs checkArgs, GradleArgs gradleArgs,
              JenkinsMetadata metadata, String buildLabel, PipelineConventions conventions) {
        this.jenkins = jenkins
        this.unityVersions = unityVersions
        this.checkArgs = checkArgs
        this.gradleArgs = gradleArgs
        this.metadata = metadata
        this.buildLabel = buildLabel
        this.conventions = conventions
    }

    @Override
    DockerArgs getDockerArgs() {
        return null
    }

    @Override
    PipelineTools getPipelineTools() {
        return PipelineTools.fromConfig(jenkins, this)
    }
}


