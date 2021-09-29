import net.wooga.jenkins.pipeline.check.Checks
import net.wooga.jenkins.pipeline.config.Config
import net.wooga.jenkins.pipeline.model.Gradle


def call(Map configMap) {
    def config = configMap.config as Config

    withEnv(["COVERALLS_PARALLEL=true"]) {
        def gradle = Gradle.fromJenkins(this, params.LOG_LEVEL?: env.LOG_LEVEL as String, params.STACK_TRACE as boolean)
        def checks = Checks.create(this, gradle, config.dockerArgs, config.metadata.buildNumber)
        def isPR = env.CHANGE_ID as boolean
        def checksForParallel = checks.javaCoverage(config, isPR)
        parallel checksForParallel
    }
}
