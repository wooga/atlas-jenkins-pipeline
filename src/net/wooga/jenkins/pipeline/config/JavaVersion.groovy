package net.wooga.jenkins.pipeline.config

class JavaVersion {

    static int resolveVersion(Object jenkins, Integer defaultJavaVersion=null, String... versionFiles) {
        return versionFiles.collect { filePath ->
            if (jenkins.fileExists(filePath)) {
                def strVersion = jenkins.readFile(filePath).trim()
                if(strVersion.contains('.')) {
                    strVersion = strVersion.substring(strVersion.indexOf('.') + 1, strVersion.length())
                }
                return Integer.valueOf(strVersion)
            }
            return null
        }.find { it != null } ?: defaultJavaVersion ?: 11
    }

    static String versionJavaHome(Object jenkins, Integer javaVersion) {
        def javaHome = jenkins.env.("JAVA_${javaVersion}_HOME".toString())
        if (javaHome) {
            return javaHome
        }
        return null
    }

    static List<String> javaHomeEnv(Object jenkins, Integer javaVersion) {
        def maybeHome = versionJavaHome(jenkins, javaVersion)
        if(maybeHome != null) {
            return["JAVA_HOME=${javaHome}"]
        }
        jenkins.echo "Could not resolve JAVA_HOME for java version ${javaVersion}"
        return []
    }
}
