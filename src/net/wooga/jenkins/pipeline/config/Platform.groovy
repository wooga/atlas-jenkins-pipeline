package net.wooga.jenkins.pipeline.config

class Platform {

    final String name
    final String labels
    final String testLabels
    final Collection<?> testEnvironment

    static Platform fromConfigMap(String platformName, Map config) {
        return new Platform(
                platformName,
                (config.labels ?: '') as String,
                mapOrCollection(platformName, config.testEnvironment),
                mapOrCollection(platformName, config.testLabels)
        )
    }

    Platform(String name, String labels, Collection<?> testEnvironment, Collection<?> testLabels) {
        this.name = name
        this.labels = labels
        this.testEnvironment = testEnvironment
        this.testLabels = testLabels?.join(" && ")
    }

    String generateTestLabelsString() {
        def generatedLabels = [name]
        if (runsOnDocker) {
            generatedLabels.add("docker")
        }
        def configLabelsStr = testLabels?: labels
        def generatedLabelsStr = generatedLabels.join(" && ")

        return configLabelsStr != null && !configLabelsStr.empty?
                "${configLabelsStr} && ${generatedLabelsStr}" :
                generatedLabelsStr
    }

    boolean getRunsOnDocker() {
        return name == "linux"
    }

    List<String> getTestEnvironment() {
        return testEnvironment.collect { item ->
            if (item instanceof Closure) {
                return item.call().toString()
            }
            return item.toString()
        }
    }

    private static Collection<?> mapOrCollection(String platformName, Object obj) {
        if(obj == null) {
            return []
        }
        if(obj instanceof Map) {
            return obj[platformName] as Collection ?: []
        }
        if(obj instanceof Collection) {
            return obj as Collection
        }
        throw new IllegalArgumentException("testEnvironment should be a collection or a Map of [platName:collection]")
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
}
