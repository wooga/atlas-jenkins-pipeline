package net.wooga.jenkins.pipeline.check.steps

import spock.lang.Specification
import spock.lang.Unroll

class BasicStepsSpec extends Specification {

    @Unroll
    def "loads java version onto environment"() {
        given:
        def environment = []
        def jenkins = [
                withEnv   : { List<String> envList, Closure cls ->
                    environment.addAll(envList)
                },
                fileExists: { String file -> fileJavaVersion != null && file == ".java-version" },
                readFile  : { String file -> file == ".java-version" ? fileJavaVersion : null },
                env       : [(expectedJavaHomeVar): "java_home"]
        ]
        when:
        def wrapper = new BasicSteps(jenkins).javaVersionWrapper(javaVersion, ".java-version")
        wrapper.call({ -> })
        then:
        environment.find { it == "JAVA_HOME=java_home" }

        where:
        javaVersion | fileJavaVersion | expectedJavaHomeVar
        null        | "1.8"           | "JAVA_8_HOME"
        1           | "12"            | "JAVA_12_HOME"
        2           | "17"            | "JAVA_17_HOME"
        2           | null            | "JAVA_2_HOME"
        null        | null            | "JAVA_11_HOME"

    }
}
