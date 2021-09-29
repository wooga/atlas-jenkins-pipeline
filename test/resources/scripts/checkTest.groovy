package scripts

import net.wooga.jenkins.pipeline.config.Config
import net.wooga.jenkins.pipeline.check.Checks
import net.wooga.jenkins.pipeline.config.WDKConfig
import net.wooga.jenkins.pipeline.model.Gradle

def call(Config config) {
    return Checks.create(this, Gradle.fromJenkins(this, null, false), config.dockerArgs, config.metadata.buildNumber)
}
def call() {
    return Checks.create(this, Gradle.fromJenkins(this, null, false), null, BUILD_NUMBER as int)
}
