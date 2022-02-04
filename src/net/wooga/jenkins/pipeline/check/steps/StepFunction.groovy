package net.wooga.jenkins.pipeline.check.steps

import net.wooga.jenkins.pipeline.config.Platform

interface StepFunction {
    void call(Platform platform)
}
