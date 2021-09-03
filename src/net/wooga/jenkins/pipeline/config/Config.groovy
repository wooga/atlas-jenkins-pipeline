package net.wooga.jenkins.pipeline.config


class Config {

    final Platform[] platforms
    final JenkinsMetadata metadata
    final DockerArgs dockerArgs
    final SonarQubeArgs sonarArgs
    final String coverallsToken

    static Config fromConfigMap(Map config,  Object jenkinsScript) {
        config.platforms = config.platforms ?: ['macos','windows']
        def platforms = config.platforms.collect { String platformName ->
            Platform.fromConfigMap(platformName, config)
        }
        def dockerArgs = DockerArgs.fromConfigMap((config.dockerArgs?: [:]) as Map)
        def sonarArgs = SonarQubeArgs.fromConfigMap(config)
        def coverallsToken = config.coverallsToken as String
        def metadata = JenkinsMetadata.fromScript(jenkinsScript)
        return new Config(metadata, platforms, dockerArgs, sonarArgs, coverallsToken)
    }

    Config(JenkinsMetadata metadata, List<Platform> platforms, DockerArgs dockerArgs, SonarQubeArgs sonarArgs,
           String coverallsToken) {
        this.metadata = metadata
        this.platforms = platforms
        this.sonarArgs = sonarArgs
        this.dockerArgs = dockerArgs
        this.coverallsToken = coverallsToken
    }
}
