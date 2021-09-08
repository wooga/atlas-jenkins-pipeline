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
    static BuildVersion parse(Object unityVerObj) {
        if(unityVerObj == null){
            throw new IllegalArgumentException("Entry cannot be null")
        }

        if (unityVerObj instanceof Closure) {
            unityVerObj = unityVerObj.call()
        }
        if (unityVerObj instanceof BuildVersion) {
            return unityVerObj
        }
        else if (unityVerObj instanceof Map) {
            def unityVerMap = unityVerObj as Map
            String version = Optional.ofNullable(unityVerMap["version"])
                    .orElseThrow{ new IllegalArgumentException("Entry ${unityVerObj} does not contain version") }
            boolean optional = unityVerMap["optional"]?: false
            String apiCompatibilityLevel = unityVerMap["apiCompatibilityLevel"]?: null
            return new BuildVersion(version, optional, apiCompatibilityLevel)
        }
        return new BuildVersion(unityVerObj.toString(), false, null)
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

/**
 * For each entry in a versions map, if they are not in the standard format
 * we process them into the newer format that supports optional arguments per version
 * (such as if it's optional, etc).
 * Example: '3001.x' -> [ version : '3001.x', optional : false ]
 * @param versions
 * @return
 */
def parseVersions(versions) {
    return versions.collect { v -> BuildVersion.parse(v) }
}
