package scripts

import net.wooga.jenkins.pipeline.config.Config
import net.wooga.jenkins.pipeline.check.Checks

def call(Config config) {
    return Checks.forConfig(this, config)
}