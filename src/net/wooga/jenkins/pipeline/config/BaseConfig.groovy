package net.wooga.jenkins.pipeline.config

class BaseConfig {
    final Object jenkins
    final PipelineConventions conventions
    final JenkinsMetadata metadata
    final GradleArgs gradleArgs
    final DockerArgs dockerArgs
    final CheckArgs checkArgs

    static BaseConfig fromConfigMap(Map configMap, Object jenkinsScript) {
        def conventions = PipelineConventions.standard.mergeWithConfigMap(configMap)
        def metadata = JenkinsMetadata.fromScript(jenkinsScript)
        def gradleArgs = GradleArgs.fromConfigMap(configMap)
        def checkArgs = CheckArgs.fromConfigMap(jenkinsScript, metadata, configMap)
        def dockerArgs = DockerArgs.fromConfigMap((configMap.dockerArgs?: [:]) as Map)

        return new BaseConfig(jenkinsScript, conventions, metadata, gradleArgs, dockerArgs, checkArgs)
    }

    BaseConfig(Object jenkins, PipelineConventions conventions, JenkinsMetadata metadata,
               GradleArgs gradleArgs, DockerArgs dockerArgs, CheckArgs checkArgs) {
        this.jenkins = jenkins
        this.conventions = conventions
        this.metadata = metadata
        this.gradleArgs = gradleArgs
        this.dockerArgs = dockerArgs
        this.checkArgs = checkArgs
    }

}
