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
                runSteps(environment, [], mainCls, catchCls, finallyCls)
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

    Closure node(Map params) {
        def fullParams = [
                label      : null,
                when       : { -> true },
                environment: [:],
                credentials: [],
                steps      : {},
                onError    : {ex -> throw ex},
                after      : { maybeException -> }
        ]
        fullParams.putAll(params)
        node(fullParams.label as String,
                fullParams.environment as Map<String, String>,
                fullParams.credentials as List,
                fullParams.when as Closure<Boolean>,
                fullParams.steps as Closure,
                fullParams.onError as Closure,
                fullParams.after as Closure)
    }

    Closure node(String label, Map<String, String> environment, List<Object> credentials, Closure<Boolean> when = { -> true },
                 Closure steps, Closure onError, Closure after) {
        return {
            if (when()) {
                def envList = environment.collect {"${it.key}=${it.value}".toString() }
                if (label) {
                    jenkins.node(label) {
                        runSteps(envList, credentials, steps, onError, after)
                    }
                } else {
                    runSteps(envList, credentials, steps, onError, after)
                }
            }
        }
    }

    private def runSteps(List<String> environment, List credentials, Closure steps, Closure onError, Closure after) {
        def maybeException = null as Exception
        jenkins.withEnv(environment) {
            jenkins.withCredentials(credentials) {
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
    }

}
