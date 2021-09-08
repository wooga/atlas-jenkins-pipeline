package scripts

import net.wooga.jenkins.pipeline.config.Config
import net.wooga.jenkins.pipeline.check.Checks
import net.wooga.jenkins.pipeline.config.WDKConfig

def call(Config config) {
    return Checks.create(this, config.dockerArgs, config.metadata.buildNumber)
}
def call() {
    return Checks.create(this, null, BUILD_NUMBER as int)
}