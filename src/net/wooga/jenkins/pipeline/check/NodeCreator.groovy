package net.wooga.jenkins.pipeline.check

class NodeCreator {

    final Object jenkins

    NodeCreator(Object jenkins) {
        this.jenkins = jenkins
    }

    Closure nodeWithEnv(String nodeLabels, List<String> environment,
                        Closure mainCls, Closure catchCls, Closure finallyCls) {
        return {
            jenkins.node(nodeLabels) {
                jenkins.withEnv(environment) {
                    runSteps(mainCls, catchCls, finallyCls)
                }
            }
        }
    }

    Closure node(Closure paramsCls) {
        def paramsMap = [:]
        def clsClone = paramsCls.clone() as Closure
        clsClone.setDelegate(paramsMap)
        clsClone(paramsMap)
        return node(paramsMap)
    }

    Closure node(Map params = [
            label  : null,
            when   : { -> true },
            steps  : {},
            onError: {},
            after  : { maybeException -> }
    ]) {
        node(params.label as String,
                params.when as Closure<Boolean>,
                params.steps as Closure,
                params.onError as Closure,
                params.after as Closure)
    }

    Closure node(String label, Closure<Boolean> when = { -> true },
                 Closure steps, Closure onError, Closure after) {
        return {
            if (when()) {
                if (label) {
                    jenkins.node(label) {
                        runSteps(steps, onError, after)
                    }
                } else {
                    runSteps(steps, onError, after)
                }
            }
        }
    }

    private static def runSteps(Closure steps, Closure onError, Closure after) {
        def maybeException = null as Exception
        try {
            steps.call()
        } catch (Exception e) {
            maybeException = e
            onError?.call(e)
        } finally {
            after?.call(maybeException)
        }
    }

}
