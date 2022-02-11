package net.wooga.jenkins.pipeline.check.steps

import net.wooga.jenkins.pipeline.config.Platform

interface StepWrapper {
    void call(Step step, Platform platform)
}
