package net.wooga.jenkins.pipeline.model

class EnvVars {

    final List<?> environment

    static EnvVars fromList(List<?> env) {
        return new EnvVars(env)
    }

    EnvVars(List<?> environment) {
        this.environment = environment
    }


    List<String> resolveAsStrings() {
        return environment.collect {
            resolveString(it)
        }
    }

    def resolveString(Object obj) {
        if(obj instanceof Closure) {
            return obj.call().toString()
        }
        return obj.toString()
    }

    Object getAt(String key) {
        environment.collect{envEntry ->
            resolveString(envEntry).split("=")}.find{
            it[0] == key
        }?.getAt(1)
    }

}
