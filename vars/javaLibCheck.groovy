import net.wooga.jenkins.pipeline.check.Checks
import net.wooga.jenkins.pipeline.config.Config


def call(Map configMap) {
    def config = configMap.config as Config
    boolean forceSonarQube = configMap.forceSonarQube

    withEnv(["COVERALLS_PARALLEL=true"]) {
        def checks = Checks.create(this, config.dockerArgs, config.metadata.buildNumber)
        def checksForParallel = checks.javaCoverage(config, forceSonarQube)
        parallel checksForParallel
    }
}