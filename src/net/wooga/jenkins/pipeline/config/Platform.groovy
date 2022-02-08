package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.BuildVersion

class Platform {

    final String checkoutDirectory
    final String checkDirectory
    final String name
    final String os
    final String labels
    final String testLabels
    final Collection<?> testEnv
    final boolean runsOnDocker
    final boolean main


    static Platform forJava(String platformName, Map config, boolean isMain) {
        return new Platform(
                (config.checkoutDir?: ".") as String,
                (config.checkDir?: ".") as String,
                platformName,
                platformName,
                platformName == "linux",
                (config.labels ?: '') as String,
                mapOrCollection(platformName, config.testEnvironment),
                mapOrCollection(platformName, config.testLabels),
                isMain
        )
    }

    static Platform forWDK(BuildVersion buildVersion, String buildOS, Map config, boolean isMain) {
        def unityEnv = ["UVM_UNITY_VERSION=${buildVersion.version}",
                        "UNITY_LOG_CATEGORY=check-${buildVersion.version}"]
        if (buildVersion.apiCompatibilityLevel != null){
            unityEnv.add("UNITY_API_COMPATIBILITY_LEVEL=${buildVersion.apiCompatibilityLevel}")
        }
        return new Platform(
                (config.checkoutDir?: buildVersion.toDirectoryName()) as String,
                (config.checkDir?: ".") as String,
                buildVersion.version,
                buildOS,
                false,
                (config.labels ?: '') as String,
                mapOrCollection(buildVersion.version, config.testEnvironment) + unityEnv,
                mapOrCollection(buildVersion.version, config.testLabels),
                isMain
        )
    }

    Platform(String checkoutDirectory, String checkDirectory, String name, String os, boolean runsOnDocker,
             String labels, Collection<?> testEnvironment, Collection<?> testLabels, boolean main) {
        this.checkoutDirectory = checkoutDirectory
        this.checkDirectory = checkDirectory
        this.name = name
        this.os = os
        this.labels = labels
        this.testEnv = testEnvironment
        this.testLabels = testLabels?.join(" && ")
        this.runsOnDocker = runsOnDocker
        this.main = main
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
        return testEnv.collect { item ->
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

        if (checkDirectory != platform.checkDirectory) return false
        if (main != platform.main) return false
        if (runsOnDocker != platform.runsOnDocker) return false
        if (labels != platform.labels) return false
        if (name != platform.name) return false
        if (os != platform.os) return false
        if (testEnv != platform.testEnvironment) return false
        if (testLabels != platform.testLabels) return false

        return true
    }

    int hashCode() {
        int result
        result = (name != null ? name.hashCode() : 0)
        result = 31 * result + (checkDirectory != null ? checkDirectory.hashCode() : 0)
        result = 31 * result + (os != null ? os.hashCode() : 0)
        result = 31 * result + (labels != null ? labels.hashCode() : 0)
        result = 31 * result + (testLabels != null ? testLabels.hashCode() : 0)
        result = 31 * result + (testEnv != null ? testEnv.hashCode() : 0)
        result = 31 * result + (runsOnDocker ? 1 : 0)
        result = 31 * result + (main ? 1 : 0)
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
