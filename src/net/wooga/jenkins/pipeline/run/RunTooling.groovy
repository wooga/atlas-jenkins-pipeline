package net.wooga.jenkins.pipeline.run

class RunTooling {

    final Closure withEnv
    final Closure gradleWrapper
    final Closure publishHTML

    void gradleWrapper(String gradleArgs) {
        this.gradleWrapper.call(gradleArgs)
    }

    void publishHTML(Map<String, Serializable> args) {
        this.publishHTML.call(args)
    }

    void withEnv(Map<String, ?> environment, Closure cls) {
        this.withEnv(environment, cls)
    }
}