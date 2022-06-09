import net.wooga.jenkins.pipeline.check.Checks
import net.wooga.jenkins.pipeline.check.steps.Step
import net.wooga.jenkins.pipeline.config.CheckArgs
import net.wooga.jenkins.pipeline.config.PipelineConfig
import net.wooga.jenkins.pipeline.config.PipelineConventions


Step call(PipelineConfig config) {
    javaCheckTemplate(config)
}

Step call(Checks checks, CheckArgs checkArgs, PipelineConventions conventions) {
    javaCheckTemplate(checks, checkArgs, conventions)
}
