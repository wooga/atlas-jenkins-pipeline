package net.wooga.jenkins.pipeline

import com.cloudbees.groovy.cps.NonCPS

class BuildVersion {


    /**
     * For each entry in a versions map, if they are not in the standard format
     * we process them into the newer format that supports optional arguments per version
     * (such as if it's optional, etc).
     * Example: '3001.x' -> [ version : '3001.x', optional : false ]
     * @param versions
     * @return
     */
    static BuildVersion parse(Object unityVerObj, File projectVersionFile = null) {
        if(unityVerObj == null){
            throw new IllegalArgumentException("Entry cannot be null")
        }
        if (unityVerObj instanceof Closure) {
            return parse(unityVerObj())
        }
        if (unityVerObj instanceof BuildVersion) {
            return new BuildVersion(parseVersionStr(unityVerObj.version, projectVersionFile), unityVerObj.optional, unityVerObj.apiCompatibilityLevel)
        }
        else if (unityVerObj instanceof Map) {
            return fromBuildVersionMap(unityVerObj as Map, projectVersionFile)
        }
        return new BuildVersion(parseVersionStr(unityVerObj.toString(), projectVersionFile), false, null)
    }

    static BuildVersion fromBuildVersionMap(Map unityVerMap, File projectVersionFile = null) {
        if(unityVerMap["version"] == null) {
            throw new IllegalArgumentException("Entry ${unityVerMap} does not contain version")
        }
        String version = unityVerMap["version"]
        boolean optional = unityVerMap["optional"]?: false
        String apiCompatibilityLevel = unityVerMap["apiCompatibilityLevel"]?: null
        return new BuildVersion(parseVersionStr(version, projectVersionFile), optional, apiCompatibilityLevel)
    }

    //TODO: test!
    static String parseVersionStr(String versionStr, File projectVersionFile = null) {
        def normalizedVersionStr = versionStr.trim().toLowerCase()
        if(normalizedVersionStr == "project_version" && projectVersionFile && projectVersionFile.exists()) {
            def versionLine = projectVersionFile.readLines().find {
                if(!it.trim().empty) {
                    def parts = it.trim().split(":")
                    def normalizedKey = parts[0].trim()
                    return normalizedKey == "m_EditorVersion"
                }
                return false
            }
            return versionLine.split(":")[1].trim()
        }
        return versionStr
    }

    final String version
    final Boolean optional
    // net_4_6, net_standard_2_0 (DEFAULT)
    final String apiCompatibilityLevel

    BuildVersion(String version, boolean optional, String apiCompatibilityLevel = null) {
        this.version = version
        this.optional = optional
        this.apiCompatibilityLevel = apiCompatibilityLevel
    }

    @Override
    @NonCPS
    String toString() {
        return version
    }

    @NonCPS
    String toLabel(){
        def result = version
        if (optional){
            result += " (optional)"
        }
        if (apiCompatibilityLevel != null) {
            result += " (${apiCompatibilityLevel})"
        }
        return result
    }

    @NonCPS
    String toDirectoryName(){
        def result = version
        if (optional){
            result += "_optional"
        }
        if (apiCompatibilityLevel != null) {
            result += "_${apiCompatibilityLevel}"
        }
        return result
    }

    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        BuildVersion that = (BuildVersion) o

        if (apiCompatibilityLevel != that.apiCompatibilityLevel) return false
        if (optional != that.optional) return false
        if (version != that.version) return false

        return true
    }

    int hashCode() {
        int result
        result = (version != null ? version.hashCode() : 0)
        result = 31 * result + (optional != null ? optional.hashCode() : 0)
        result = 31 * result + (apiCompatibilityLevel != null ? apiCompatibilityLevel.hashCode() : 0)
        return result
    }
}
