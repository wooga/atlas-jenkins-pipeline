package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.PipelineTools

class JavaConfig implements PipelineConfig {

    final Object jenkins

    final PipelineConventions conventions
    final Platform[] platforms
    final JenkinsMetadata metadata
    final GradleArgs gradleArgs
    final DockerArgs dockerArgs
    final CheckArgs checkArgs

    static JavaConfig fromConfigMap(Map config, Object jenkinsScript) {
        config.platforms = config.platforms ?: ['macos','windows']
        def platNames = config.platforms as List<String>
        def index = 0
        def platforms = platNames.collect { String platformName ->
            def platform = Platform.forJava(platformName, config, index == 0)
            index++
            return platform
        }
        def metadata = JenkinsMetadata.fromScript(jenkinsScript)
        def gradleArgs = GradleArgs.fromConfigMap(config)
        def checkArgs = CheckArgs.fromConfigMap(jenkinsScript, metadata, config)
        def dockerArgs = DockerArgs.fromConfigMap((config.dockerArgs?: [:]) as Map)
        def conventions = PipelineConventions.standard.mergeWithConfigMap(config)

        return new JavaConfig(jenkinsScript, metadata, platforms, gradleArgs, dockerArgs, checkArgs, conventions)
    }

    JavaConfig(Object jenkinsScript, JenkinsMetadata metadata, List<Platform> platforms,
               GradleArgs gradleArgs, DockerArgs dockerArgs, CheckArgs checkArgs, PipelineConventions conventions) {
        this.jenkins = jenkinsScript
        this.metadata = metadata
        this.platforms = platforms
        this.gradleArgs = gradleArgs
        this.dockerArgs = dockerArgs
        this.checkArgs = checkArgs
        this.conventions = conventions
    }

    Platform getMainPlatform() {
        return platforms.find {it.main }
    }

    @Override
    PipelineTools getPipelineTools() {
        return PipelineTools.fromConfig(jenkins, this)
    }

    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        JavaConfig that = (JavaConfig) o

        if (checkArgs != that.checkArgs) return false
        if (conventions != that.conventions) return false
        if (dockerArgs != that.dockerArgs) return false
        if (gradleArgs != that.gradleArgs) return false
        if (jenkins != that.jenkins) return false
        if (metadata != that.metadata) return false
        if (!Arrays.equals(platforms, that.platforms)) return false

        return true
    }

    int hashCode() {
        int result
        result = (jenkins != null ? jenkins.hashCode() : 0)
        result = 31 * result + (conventions != null ? conventions.hashCode() : 0)
        result = 31 * result + (platforms != null ? Arrays.hashCode(platforms) : 0)
        result = 31 * result + (metadata != null ? metadata.hashCode() : 0)
        result = 31 * result + (gradleArgs != null ? gradleArgs.hashCode() : 0)
        result = 31 * result + (dockerArgs != null ? dockerArgs.hashCode() : 0)
        result = 31 * result + (checkArgs != null ? checkArgs.hashCode() : 0)
        return result
    }
}
