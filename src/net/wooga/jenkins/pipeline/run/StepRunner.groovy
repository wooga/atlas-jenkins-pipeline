package net.wooga.jenkins.pipeline.run

interface StepRunner {
    void env(Map<String, ?> environment)
    void gradleRun(String args)
    void gradleRun(String args, Closure after)
}
