package net.wooga.jenkins.pipeline

import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

class BuildVersion {

    final String version
    final Boolean optional
    // net_4_6, net_standard_2_0 (DEFAULT)
    final String apiCompatibilityLevel

    BuildVersion(version, optional, apiCompatibilityLevel = null) {
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

    final versionKey = "version"
    final optionalKey = "optional"
    final apiCompatibilityLevelKey = "apiCompatibilityLevel"

    versions.collect { v ->

        if (v instanceof Closure) {
            v = v.call()
        }

        if (v instanceof Map) {

            // Version
            if (!v.containsKey(versionKey)) {
                throw new Exception("Entry ${v} does not contain ${versionKey}")
            }
            // Optional
            if (!v.containsKey(optionalKey)) {
                throw new Exception("Entry ${v} does not contain ${optionalKey}")
            }
            // API Compatibility Level
            def apiCompatibilityLevel = v[apiCompatibilityLevelKey]

            return new BuildVersion(v[versionKey], v[optionalKey], apiCompatibilityLevel)
        }

        return new BuildVersion(v.toString(), false, null)
    }
}
