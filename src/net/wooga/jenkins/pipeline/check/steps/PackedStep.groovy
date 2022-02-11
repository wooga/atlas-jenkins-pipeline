package net.wooga.jenkins.pipeline.check.steps

//this is a fucking java.lang.Runnable, but muh jenkins sandbox
interface PackedStep {
    void call()
}
