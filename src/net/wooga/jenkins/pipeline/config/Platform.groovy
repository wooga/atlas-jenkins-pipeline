package net.wooga.jenkins.pipeline.config

import groovy.transform.EqualsAndHashCode

@EqualsAndHashCode
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
        def nodeLabels = testLabels?: labels
        nodeLabels = "${nodeLabels} && ${name}"
        if (runsOnDocker) {
            nodeLabels = "${nodeLabels} && docker"
        }
        return nodeLabels
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
}
