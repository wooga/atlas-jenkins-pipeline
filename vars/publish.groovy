import net.wooga.jenkins.pipeline.model.Gradle
import net.wooga.jenkins.pipeline.publish.Publishers

def call(String releaseType, String releaseScope, Closure commands) {
    def gradle = Gradle.fromJenkins(this, params.LOG_LEVEL?: env.LOG_LEVEL as String, params.STACK_TRACE as boolean)
    def publisher = Publishers.fromJenkins(this, gradle, releaseType, releaseScope)
    commands.delegate = publisher
    commands(publisher)
}