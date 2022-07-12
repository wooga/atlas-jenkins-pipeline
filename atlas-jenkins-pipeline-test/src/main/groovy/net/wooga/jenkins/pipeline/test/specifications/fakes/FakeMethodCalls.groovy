package net.wooga.jenkins.pipeline.test.specifications.fakes

import com.lesfurets.jenkins.unit.MethodCall
import com.lesfurets.jenkins.unit.PipelineTestHelper


class FakeMethodCalls {

    class Has {
        Closure getAt(String propertyName) {
            return this.&hasMethodCallWith.curry(propertyName)
        }
        protected boolean hasMethodCallWith(String methodName, Closure<Boolean> assertion) {
            return getMethodCalls(methodName).any(assertion)
        }
    }

    final PipelineTestHelper helper
    final Has has

    FakeMethodCalls(PipelineTestHelper helper) {
        this.helper = helper
        this.has = new Has()
    }

    MethodCall[] getAt(String propertyName) {
        return getMethodCalls(propertyName)
    }

    protected MethodCall[] getMethodCalls(String methodName) {
        return helper.callStack.findAll { call ->
            call.methodName == methodName
        }
    }
}
