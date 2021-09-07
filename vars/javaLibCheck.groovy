import net.wooga.jenkins.pipeline.check.Checks
import net.wooga.jenkins.pipeline.config.Config


def call(Map configMap) {
    def config = configMap.config as Config
    boolean forceSonarQube = configMap.forceSonarQube

    withEnv(["COVERALLS_PARALLEL=true"]) {
        def checks = Checks.forConfig(this, config)
        def checksForParallel = checks.withCoverage(forceSonarQube)
        parallel checksForParallel
    }
}