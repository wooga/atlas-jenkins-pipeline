package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.BuildVersion

class Platform {

    final String name
    final String os
    final boolean runsOnDocker
    final String labels
    final String testLabels
    final Collection<?> testEnvironment


    static Platform forJava(String platformName, Map config) {
        return new Platform(
                platformName,
                platformName,
                config.dockerArgs != null && platformName == "linux",
                (config.labels ?: '') as String,
                mapOrCollection(platformName, config.testEnvironment),
                mapOrCollection(platformName, config.testLabels)
        )
    }

    static Platform forWDK(BuildVersion buildVersion, String buildOS, Map config) {
        def unityEnv = ["UVM_UNITY_VERSION=${buildVersion.version}",
                        "UNITY_LOG_CATEGORY=check-${buildVersion.version}"]
        if (buildVersion.apiCompatibilityLevel != null){
            unityEnv.add("UNITY_API_COMPATIBILITY_LEVEL=${buildVersion.apiCompatibilityLevel}")
        }
        return new Platform(
                buildVersion.version,
                buildOS,
                false,
                (config.labels ?: '') as String,
                mapOrCollection(buildVersion.version, config.testEnvironment) + unityEnv,
                mapOrCollection(buildVersion.version, config.testLabels)
        )
    }

    Platform(String name, String os, boolean runsOnDocker,
             String labels, Collection<?> testEnvironment, Collection<?> testLabels) {
        this.name = name
        this.os = os
        this.runsOnDocker = runsOnDocker
        this.labels = labels
        this.testEnvironment = testEnvironment
        this.testLabels = testLabels?.join(" && ")
    }

    String generateTestLabelsString() {
        def generatedLabels = [os]
        if (runsOnDocker) {
            generatedLabels.add("docker")
        }
        def configLabelsStr = testLabels?: labels
        def generatedLabelsStr = generatedLabels.join(" && ")

        return configLabelsStr != null && !configLabelsStr.empty?
                "${configLabelsStr} && ${generatedLabelsStr}".toString() :
                generatedLabelsStr
    }

    List<String> getTestEnvironment() {
        return testEnvironment.collect { item ->
            if (item instanceof Closure) {
                return item.call().toString()
            }
            return item.toString()
        }
    }


    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        Platform platform = (Platform) o

        if (labels != platform.labels) return false
        if (name != platform.name) return false
        if (testEnvironment != platform.testEnvironment) return false
        if (testLabels != platform.testLabels) return false

        return true
    }

    int hashCode() {
        int result
        result = (name != null ? name.hashCode() : 0)
        result = 31 * result + (labels != null ? labels.hashCode() : 0)
        result = 31 * result + (testLabels != null ? testLabels.hashCode() : 0)
        result = 31 * result + (testEnvironment != null ? testEnvironment.hashCode() : 0)
        return result
    }

    protected static Collection<?> mapOrCollection(String mapKey, Object obj) {
        if(obj == null) {
            return []
        }
        if(obj instanceof Map) {
            def value = obj[mapKey]
            if(value instanceof String || value instanceof GString) {
                return [value?.toString()]?: []
            } else {
                return obj[mapKey] as Collection ?: []
            }
        }
        if(obj instanceof Collection) {
            return obj as Collection
        }
        throw new IllegalArgumentException("${obj} should be a Collection or a Map of [key:collection] or [key:string]")
    }
}
