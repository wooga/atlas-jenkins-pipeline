package net.wooga.jenkins.pipeline.run

class JenkinsStepRunner implements StepRunner {

    Map<String, ?> environment
    RunTooling tools

    JenkinsStepRunner(Map<String, ?> environment, RunTooling tools) {
        this.environment = environment
        this.tools = tools
    }

    @Override
    void env(Map<String, ?> environment) {
        this.environment.putAll(environment)
    }

    @Override
    void gradleRun(String args, Closure after={}) {
        tools.withEnv(environment) {
            tools.gradleWrapper(args)
            def afterClone = after.clone() as Closure
            afterClone.setDelegate(tools)
            afterClone.call(tools)
        }
    }
}
