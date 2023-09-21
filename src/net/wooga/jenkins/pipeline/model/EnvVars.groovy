package net.wooga.jenkins.pipeline.model

class EnvVars {

    final Map<String, Closure<String>> environment

    EnvVars(Map<String, ?> environment) {
        this.environment = lazifyMap(environment)
    }

    static Map<String, Closure<String>> lazifyMap(Map<String, ?> env) {
        Map<String, Closure<String>> lazyMap = [:]
        for(String key : env.keySet()) {
            if(env[key] instanceof Closure) {
                lazyMap.put(key, env[key])
            } else {
                def value = env.get(key)
                lazyMap.put(key, { -> value })
            }
        }
        return lazyMap
    }

    List<String> resolveAsStrings() {
        List<String> result = []
        for(String key : environment.keySet()) {
            Closure<String> lazyValue = environment[key]
            def value = lazyValue?.call()
            if(value != null) {
                result += ["$key=$value"]
            }
        }
        return result
    }

    Closure<String> getAt(String key) {
        environment[key]
    }

}
