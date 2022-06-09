import net.wooga.jenkins.pipeline.check.steps.Step
import net.wooga.jenkins.pipeline.config.Platform

Map<String, Closure> call(Step checkTemplate, Platform[] platforms, String parallelPrefix) {
    return call(checkTemplate.stepFunction.&call, platforms, parallelPrefix)
}

Map<String, Closure> call(Closure checkTemplate, Platform[] platforms, String parallelPrefix) {
    return platforms.collectEntries { platform ->
        String parallelStepName = "${parallelPrefix}${platform.name}"
        def check = checkTemplate.clone()
        return [(parallelStepName): { check(platform) }]
    }
}
