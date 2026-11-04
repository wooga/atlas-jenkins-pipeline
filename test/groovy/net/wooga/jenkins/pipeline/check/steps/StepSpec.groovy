package net.wooga.jenkins.pipeline.check.steps

import net.wooga.jenkins.pipeline.config.Platform
import spock.lang.Specification

class StepSpec extends Specification {

    def "runs the given closure for a platform"() {
        given:
        def ran = false
        def step = new Step({ Platform platform -> ran = true })

        when:
        step.call(null)

        then:
        ran
        step.stepFunction instanceof Closure
    }

    def "packed step runs the closure"() {
        given:
        def ran = false
        def step = new Step({ Platform platform -> ran = true })

        when:
        step.pack(null).call()

        then:
        ran
    }

    def "wrapped step runs the wrapper around the original closure"() {
        given:
        def order = []
        def step = new Step({ Platform platform -> order << "step" })
        StepWrapper wrapper = { Step wrapped, Platform platform ->
            order << "before"
            wrapped.call(platform)
            order << "after"
        }

        when:
        step.wrappedBy(wrapper).call(null)

        then:
        order == ["before", "step", "after"]
    }
}
