import net.wooga.jenkins.pipeline.check.Checks
import net.wooga.jenkins.pipeline.check.EnclosureCreator
import net.wooga.jenkins.pipeline.check.Enclosures
import net.wooga.jenkins.pipeline.config.DockerArgs
import net.wooga.jenkins.pipeline.model.Docker


def call(Map dockerArgs = [:]) {
    def docker = Docker.fromJenkins(this, DockerArgs.fromConfigMap(dockerArgs))
    def enclosureCreator = new EnclosureCreator(this, BUILD_NUMBER) //from jenkins env
    def enclosures = new Enclosures(this, docker, enclosureCreator)
    return new Checks(this, enclosures)
}
