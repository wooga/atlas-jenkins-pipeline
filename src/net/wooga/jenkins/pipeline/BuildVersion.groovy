package net.wooga.jenkins.pipeline

import com.cloudbees.groovy.cps.NonCPS

class BuildVersion {

    static final String AUTO_UNITY_VERSION = "project_version"

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
            return parse(unityVerObj())
        }
        if (unityVerObj instanceof BuildVersion) {
            return new BuildVersion(unityVerObj.label, unityVerObj.version, unityVerObj.optional, unityVerObj.apiCompatibilityLevel)
        }
        else if (unityVerObj instanceof Map) {
            return fromBuildVersionMap(unityVerObj as Map)
        }
        return new BuildVersion(unityVerObj.toString(), false, null)
    }

    static BuildVersion fromBuildVersionMap(Map unityVerMap) {
        if(unityVerMap["version"] == null) {
            throw new IllegalArgumentException("Entry ${unityVerMap} does not contain version")
        }
        String label = unityVerMap["label"]?: "macos"
        String version = unityVerMap["version"]
        boolean optional = unityVerMap["optional"]?: false
        String apiCompatibilityLevel = unityVerMap["apiCompatibilityLevel"]?: null
        return new BuildVersion(label, version, optional, apiCompatibilityLevel)
    }

    final String label
    final String version
    final Boolean optional
    // net_4_6, net_standard_2_0 (DEFAULT)
    final String apiCompatibilityLevel

    /**
     * Constructor to keep backwards compatibility. Assigns 'macos' to the label field, please use
     * BuildVersion(String label, String version, boolean optional, String apiCompatibilityLevel) instead
     * @param version
     * @param optional
     * @param apiCompatibilityLevel
     */
    @Deprecated
    BuildVersion(String version, boolean optional, String apiCompatibilityLevel = null) {
        this("macos", version, optional, apiCompatibilityLevel)
    }

    BuildVersion(String label, String version, boolean optional, String apiCompatibilityLevel = null) {
        this.label = label
        this.version = version
        this.optional = optional
        this.apiCompatibilityLevel = apiCompatibilityLevel
    }

    @Override
    @NonCPS
    String toString() {
        return version
    }

    /**
     * Please use toDescription() instead
     * @return
     */
    @NonCPS
    @Deprecated
    String toLabel() {
        return toDescription()
    }

    @NonCPS
    String toDescription() {
        def result = "$label Unity-$version"
        if (optional){
            result += " (optional)"
        }
        if (apiCompatibilityLevel != null) {
            result += " (${apiCompatibilityLevel})"
        }
        return result
    }

    @NonCPS
    String toDirectoryName() {
        def result = version
        if (optional){
            result += "_optional"
        }
        if (apiCompatibilityLevel != null) {
            result += "_${apiCompatibilityLevel}"
        }
        return result
    }

    boolean hasVersion() {
        return version != AUTO_UNITY_VERSION
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
