package tools

class GradleInvocation {

    static def parseCommandLine(String cmdGradleCall) {
        def elements = cmdGradleCall.split(" ")
                .collect {it.trim()}
                .findAll{ it != null & !it.empty }
                .findAll{!(it.endsWith("gradlew") || it.endsWith("gradlew.bat")) }
        def propertiesRaw = elements.findAll{it.startsWith("-P") }
        def properties = propertiesRaw.collectEntries {raw ->
            def parts = raw.split("=")
            def key = parts[0].replaceAll("-P","").trim()
            def value = parts.size() > 1? parts[1] : null
            return [(key): value]
        } as Map<String, String>

        def tasks = elements.findAll {!it.startsWith("-") }

        return new GradleInvocation(cmdGradleCall, tasks, properties)
    }

    String raw
    String[] tasks
    Map<String, String> properties

    GradleInvocation(String raw, List<String> tasks, Map<String, String> properties) {
        this.raw = raw
        this.tasks = tasks
        this.properties = properties
    }

    def getAt(String propertyKey) {
        return properties[propertyKey]
    }

    boolean containsRaw(String s) {
        return raw.contains(s)
    }
}

