package net.wooga.jenkins.pipeline.check.steps


import net.wooga.jenkins.pipeline.config.Platform

class Step {

    Closure stepFunction

    // Keep the closure as a Closure. `closure as StepFunction` is rejected by the Jenkins sandbox
    // (SECURITY-3931): StepFunction.call takes arguments, and a CPS closure already has inherited call overloads.
    Step(Closure closure) {
        this.stepFunction = closure
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
