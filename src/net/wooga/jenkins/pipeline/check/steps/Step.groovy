package net.wooga.jenkins.pipeline.check.steps


import net.wooga.jenkins.pipeline.config.Platform

class Step {

    final static StepWrapper identityWrapper = { step, plat -> step(plat) }

    StepFunction stepFunction

    Step(Closure closure) {
        this(closure as StepFunction)
    }

    Step(StepFunction stepFunction) {
        this.stepFunction = stepFunction
    }

    Step wrappedBy(StepWrapper wrapper) {
        Step self = this
        return new Step({ Platform platform ->
            wrapper.call(self, platform)
        })
    }

    void call(Platform platform) {
        stepFunction.call(platform)
    }

    PackedStep pack(Platform platform) {
        return { -> stepFunction.call(platform)}
    }
}
