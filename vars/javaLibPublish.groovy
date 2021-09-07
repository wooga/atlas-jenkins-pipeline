import net.wooga.jenkins.pipeline.publish.Publishers

def call(String releaseType, String releaseScope, Closure commands) {
    def publisher = Publishers.fromJenkins(this, releaseType, releaseScope)
    commands.delegate = publisher
    commands(publisher)
}