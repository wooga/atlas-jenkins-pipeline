import net.wooga.jenkins.pipeline.config.JavaConfig

def call(Map configMap) {
    def config = configMap.config as JavaConfig
    withEnv(["COVERALLS_PARALLEL=true"]) {
        def javaChecks = config.pipelineTools.checks.forJavaPipelines()
        def checksForParallel = javaChecks.gradleCheckWithCoverage(config.platforms, config.checkArgs, config.conventions)
        parallel checksForParallel
    }
}
