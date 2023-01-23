package tools

import net.wooga.jenkins.pipeline.test.specifications.SandboxedDeclarativeJenkinsSpec

class DeclarativeJenkinsSpec extends SandboxedDeclarativeJenkinsSpec {

    def setupSpec() {
        binding.with {
            setProperty("docker", [:])
        }
    }

    def setup() {
        helper.with {
            registerAllowedMethod("istanbulCoberturaAdapter", [String]) {}
            registerAllowedMethod("isUnix") { true }
            registerAllowedMethod("sendSlackNotification", [String, boolean]) {}
            registerAllowedMethod("junit", [LinkedHashMap]) {}
            registerAllowedMethod("nunit", [LinkedHashMap]) {}
            registerAllowedMethod("catchError", [LinkedHashMap, Closure]) {}

        }
    }

    List<Map> getUsedEnvironments() {
        return environment.used
    }

    String[] getShGradleCalls() {
        return calls["sh"].collect { it.args[0]["script"].toString() }.findAll {
            it.contains("gradlew")
        }
    }

    @Override
    String[] getWhitelistedPackages() {
        return ["net.wooga.jenkins.pipeline"]
    }

    Script loadSandboxedScript(String path, Closure varBindingOps={}) {
        return super.loadSandboxedScript(path, ["vars/javaLibs.groovy"], varBindingOps)
    }
}
