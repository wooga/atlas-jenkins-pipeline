package net.wooga.jenkins.pipeline.step

class CompositeStep implements Step {

    private Step[] steps

    public CompositeStep(Step[] steps) {
        this.steps = steps
    }

    @Override
    String getGradleArgs() {
        return steps.collect {step ->
            return step.gradleArgs
        }.join(" ")
    }
}
