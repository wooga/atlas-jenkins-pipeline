import net.wooga.jenkins.pipeline.model.Gradle
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

def call() {
    return [
            stringToSHA1 : {String content -> Utils.stringToSHA1(content)}
    ]
}
