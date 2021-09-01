package net.wooga.jenkins.pipeline.step

class SonarQubeStep implements Step {

    private String token
    private Map<String,String> properties

    SonarQubeStep(String token, Map<String, String> extraProperties) {
        this.token = token
        this.properties = extraProperties
    }

    public String getGradleArgs() {
        properties["sonar.login"] =  token
        def propertiesStr = properties.collect {"-D${it.key}=${it.value}"}.join(" ")
        return "sonarqube ${propertiesStr}"
    }
}
