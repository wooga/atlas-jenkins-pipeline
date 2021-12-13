package net.wooga.jenkins.pipeline.config

class JavaConfig implements PipelineConfig {

    final Platform[] platforms
    final JenkinsMetadata metadata
    final DockerArgs dockerArgs
    final SonarQubeArgs sonarArgs
    final GradleArgs gradleArgs
    final String coverallsToken

    static JavaConfig fromConfigMap(Map config, Object jenkinsScript) {
        config.platforms = config.platforms ?: ['macos','windows']
        def platNames = config.platforms as List<String>
        def index = 0
        def platforms = platNames.collect { String platformName ->
            def platform = Platform.forJava(platformName, config, index == 0)
            index++
            return platform
        }
        def dockerArgs = DockerArgs.fromConfigMap((config.dockerArgs?: [:]) as Map)
        def sonarArgs = SonarQubeArgs.fromConfigMap(config)
        def gradleArgs = GradleArgs.fromConfigMap(config)
        def coverallsToken = config.coverallsToken as String
        def metadata = JenkinsMetadata.fromScript(jenkinsScript)
        return new JavaConfig(metadata, platforms, dockerArgs, sonarArgs, gradleArgs, coverallsToken)
    }

    JavaConfig(JenkinsMetadata metadata, List<Platform> platforms,
               DockerArgs dockerArgs, SonarQubeArgs sonarArgs, GradleArgs gradleArgs,
               String coverallsToken) {
        this.metadata = metadata
        this.platforms = platforms
        this.sonarArgs = sonarArgs
        this.dockerArgs = dockerArgs
        this.gradleArgs = gradleArgs
        this.coverallsToken = coverallsToken
    }

    Platform getMainPlatform() {
        return platforms.find {it.main }
    }


    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        JavaConfig config = (JavaConfig) o

        if (coverallsToken != config.coverallsToken) return false
        if (dockerArgs != config.dockerArgs) return false
        if (metadata != config.metadata) return false
        if (!Arrays.equals(platforms, config.platforms)) return false
        if (sonarArgs != config.sonarArgs) return false

        return true
    }

    int hashCode() {
        int result
        result = (platforms != null ? Arrays.hashCode(platforms) : 0)
        result = 31 * result + (metadata != null ? metadata.hashCode() : 0)
        result = 31 * result + (dockerArgs != null ? dockerArgs.hashCode() : 0)
        result = 31 * result + (sonarArgs != null ? sonarArgs.hashCode() : 0)
        result = 31 * result + (coverallsToken != null ? coverallsToken.hashCode() : 0)
        return result
    }
}
